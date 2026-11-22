package com.example.library.catalog;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.resolver.*;
import io.netty.util.concurrent.*;

final class SocksTlsFixture implements AutoCloseable {
    record SocksRequest(int method, int addressType, String domain, int port) { }
    final List<SocksRequest> socksRequests = new CopyOnWriteArrayList<>();
    final List<String> httpRequests = new CopyOnWriteArrayList<>();
    final List<String> serverNames = new CopyOnWriteArrayList<>();
    final List<Throwable> failures = new CopyOnWriteArrayList<>();
    final CountDownLatch bodyStarted = new CountDownLatch(1);
    volatile int responseStatus = 200;
    volatile byte[] responseBody = "{\"ISBN:080442957X\":{\"title\":\"fixture book\"}}".getBytes(StandardCharsets.UTF_8);
    volatile boolean stallBody;
    volatile boolean rejectAuth;
    volatile int proxyHandshakeDelayMillis;
    volatile int tlsHandshakeDelayMillis;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ExecutorService threads = Executors.newFixedThreadPool(10);
    private final SSLServerSocket upstream;
    private final ServerSocket proxy;
    private volatile boolean closed;
    private static final char[] PASSWORD = "fixture-only".toCharArray();
    private static final KeyStore KEYS = keys("openlibrary.org,dns:covers.openlibrary.org");
    private final KeyStore selectedKeys;

    private static KeyStore keys(String names) {
        try {
            Path dir = Files.createTempDirectory("proxy-tls-fixture-");
            Path store = dir.resolve("fixture.p12");
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                    "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                    "-dname", "CN=openlibrary.org", "-ext", "SAN=dns:" + names,
                    "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", new String(PASSWORD))
                    .redirectErrorStream(true).start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("fixture key generation timeout"); }
            if (process.exitValue() != 0) { throw new IOException("fixture key generation failed"); }
            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(store)) { keys.load(input, PASSWORD); }
            Files.delete(store); Files.delete(dir);
            return keys;
        } catch (Exception ex) { throw new ExceptionInInitializerError(ex); }
    }

    SocksTlsFixture() throws Exception { this(false); }
    SocksTlsFixture(boolean wrongHostname) throws Exception {
        selectedKeys = wrongHostname ? keys("wrong-host.invalid") : KEYS;
        var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); managers.init(selectedKeys, PASSWORD);
        var ssl = SSLContext.getInstance("TLS"); ssl.init(managers.getKeyManagers(), null, null);
        upstream = (SSLServerSocket) ssl.getServerSocketFactory().createServerSocket(0, 16, InetAddress.getLoopbackAddress());
        proxy = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
        threads.submit(() -> accept(upstream, this::http));
        threads.submit(() -> accept(proxy, this::socks));
    }
    int proxyPort() { return proxy.getLocalPort(); }
    SslContext clientTls() throws Exception {
        return SslContextBuilder.forClient().sslProvider(SslProvider.JDK).trustManager((X509Certificate) selectedKeys.getCertificate("fixture")).build();
    }
    AddressResolverGroup<InetSocketAddress> directAddresses() {
        return new AddressResolverGroup<>() {
            @Override protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
                return new AbstractAddressResolver<>(executor, InetSocketAddress.class) {
                    protected boolean doIsResolved(InetSocketAddress address) { return false; }
                    protected void doResolve(InetSocketAddress address, Promise<InetSocketAddress> promise) {
                        promise.setSuccess(new InetSocketAddress(InetAddress.getLoopbackAddress(), upstream.getLocalPort()));
                    }
                    protected void doResolveAll(InetSocketAddress address, Promise<List<InetSocketAddress>> promise) {
                        promise.setSuccess(List.of(new InetSocketAddress(InetAddress.getLoopbackAddress(), upstream.getLocalPort())));
                    }
                };
            }
        };
    }
    private interface Handler { void handle(Socket socket) throws Exception; }
    private void accept(ServerSocket server, Handler handler) {
        while (!closed) {
            try {
                Socket socket = server.accept(); sockets.add(socket); socket.setSoTimeout(4000);
                threads.submit(() -> {
                    try (socket) { handler.handle(socket); }
                    catch (Exception ex) { if (!closed && !(ex instanceof SocketException) && !(ex instanceof SSLException)) { failures.add(ex); } }
                    finally { sockets.remove(socket); }
                });
            } catch (IOException ex) { if (!closed) { failures.add(ex); } }
        }
    }
    private void http(Socket socket) throws Exception {
        var tls = (SSLSocket) socket;
        if (tlsHandshakeDelayMillis > 0) { TimeUnit.MILLISECONDS.sleep(tlsHandshakeDelayMillis); }
        tls.startHandshake();
        var session = (ExtendedSSLSession) tls.getSession();
        for (var name : session.getRequestedServerNames()) { if (name instanceof SNIHostName host) { serverNames.add(host.getAsciiName()); } }
        var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        StringBuilder request = new StringBuilder();
        for (String line; (line = reader.readLine()) != null && !line.isEmpty();) { request.append(line).append('\n'); }
        httpRequests.add(request.toString());
        byte[] body = request.toString().contains("/b/") ? BookCoverApiTest.image("png", 4, 6) : responseBody;
        String type = request.toString().contains("/b/") ? "image/png" : "application/json";
        var out = socket.getOutputStream();
        out.write(("HTTP/1.1 " + responseStatus + " Fixture\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        if (stallBody) { out.write(body, 0, 1); out.flush(); bodyStarted.countDown(); while (!closed && socket.getInputStream().read() != -1) { } }
        else { out.write(body); out.flush(); }
    }
    private void socks(Socket socket) throws Exception {
        var in = new DataInputStream(socket.getInputStream()); var out = socket.getOutputStream();
        if (in.readUnsignedByte() != 5) { throw new IOException("not SOCKS5"); }
        int methods = in.readUnsignedByte(); byte[] offered = in.readNBytes(methods);
        if (!Arrays.equals(offered, new byte[]{0})) { throw new IOException("not NO_AUTH only"); }
        if (rejectAuth) { out.write(new byte[]{5, (byte) 255}); out.flush(); socksRequests.add(new SocksRequest(255, 0, "", 0)); return; }
        if (proxyHandshakeDelayMillis > 0) { TimeUnit.MILLISECONDS.sleep(proxyHandshakeDelayMillis); }
        out.write(new byte[]{5, 0}); out.flush();
        if (in.readUnsignedByte() != 5 || in.readUnsignedByte() != 1 || in.readUnsignedByte() != 0) { throw new IOException("not CONNECT command"); }
        int type = in.readUnsignedByte(); if (type != 3) { throw new IOException("upstream DNS not delegated to proxy"); }
        String domain = new String(in.readNBytes(in.readUnsignedByte()), StandardCharsets.US_ASCII);
        int port = in.readUnsignedShort(); socksRequests.add(new SocksRequest(0, type, domain, port));
        Socket remote = new Socket(InetAddress.getLoopbackAddress(), upstream.getLocalPort()); sockets.add(remote);
        try (remote) {
            out.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, 0, 0}); out.flush();
            var outbound = threads.submit(() -> pipe(socket, remote));
            pipe(remote, socket); outbound.get(5, TimeUnit.SECONDS);
        } finally { sockets.remove(remote); }
    }
    private static void pipe(Socket from, Socket to) {
        try { from.getInputStream().transferTo(to.getOutputStream()); to.shutdownOutput(); }
        catch (IOException ignored) { }
    }
    @Override public void close() throws Exception {
        closed = true; proxy.close(); upstream.close();
        for (Socket socket : sockets) { socket.close(); }
        threads.shutdownNow(); if (!threads.awaitTermination(5, TimeUnit.SECONDS)) { throw new IOException("fixture workers not terminated"); }
    }
}
