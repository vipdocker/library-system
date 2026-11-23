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

/**
 * Local-only ShowAPI fixture: TLS upstream (SNI route.showapi.com / static1.showapi.com), plain HTTP
 * upstream for documented port-80 covers, and an optional SOCKS5 proxy. Fake credentials only.
 */
final class ShowApiFixture implements AutoCloseable {
    record SocksRequest(String domain, int port) { }
    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<String> bodies = new CopyOnWriteArrayList<>();
    final List<String> serverNames = new CopyOnWriteArrayList<>();
    final List<SocksRequest> socksRequests = new CopyOnWriteArrayList<>();
    final List<Throwable> failures = new CopyOnWriteArrayList<>();
    volatile int status = 200;
    volatile byte[] responseBody = "{\"showapi_res_code\":0}".getBytes(StandardCharsets.UTF_8);
    volatile String contentType = "application/json";
    volatile String retryAfter;
    volatile String location;
    volatile boolean stallBody;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ExecutorService threads = Executors.newFixedThreadPool(10);
    private final SSLServerSocket tls;
    private final ServerSocket plain;
    private final ServerSocket proxy;
    private volatile boolean closed;
    private static final char[] PASSWORD = "fixture-only".toCharArray();
    private static final KeyStore KEYS = keys();

    private static KeyStore keys() {
        try {
            Path dir = Files.createTempDirectory("showapi-tls-fixture-");
            Path store = dir.resolve("fixture.p12");
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                    "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                    "-dname", "CN=route.showapi.com", "-ext", "SAN=dns:route.showapi.com,dns:static1.showapi.com",
                    "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", new String(PASSWORD))
                    .redirectErrorStream(true).start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("fixture key generation timeout"); }
            if (process.exitValue() != 0) { throw new IOException("fixture key generation failed"); }
            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(store)) { keys.load(input, PASSWORD); }
            Files.delete(store);
            Files.delete(dir);
            return keys;
        } catch (Exception ex) { throw new ExceptionInInitializerError(ex); }
    }

    ShowApiFixture() throws Exception {
        var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(KEYS, PASSWORD);
        var ssl = SSLContext.getInstance("TLS");
        ssl.init(managers.getKeyManagers(), null, null);
        tls = (SSLServerSocket) ssl.getServerSocketFactory().createServerSocket(0, 16, InetAddress.getLoopbackAddress());
        plain = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
        proxy = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
        threads.submit(() -> accept(tls, socket -> http(socket, true)));
        threads.submit(() -> accept(plain, socket -> http(socket, false)));
        threads.submit(() -> accept(proxy, this::socks));
    }

    int proxyPort() { return proxy.getLocalPort(); }

    SslContext clientTls() throws Exception {
        return SslContextBuilder.forClient().sslProvider(SslProvider.JDK)
                .trustManager((X509Certificate) KEYS.getCertificate("fixture")).build();
    }

    /** Maps the URI's default port to the local fixture socket; proves connections use injected resolution. */
    AddressResolverGroup<InetSocketAddress> resolver() {
        return new AddressResolverGroup<>() {
            @Override protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
                return new AbstractAddressResolver<>(executor, InetSocketAddress.class) {
                    protected boolean doIsResolved(InetSocketAddress address) { return false; }
                    protected void doResolve(InetSocketAddress address, Promise<InetSocketAddress> promise) {
                        promise.setSuccess(target(address.getPort()));
                    }
                    protected void doResolveAll(InetSocketAddress address, Promise<List<InetSocketAddress>> promise) {
                        promise.setSuccess(List.of(target(address.getPort())));
                    }
                    private InetSocketAddress target(int port) {
                        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port == 80 ? plain.getLocalPort() : tls.getLocalPort());
                    }
                };
            }
        };
    }

    private interface Handler { void handle(Socket socket) throws Exception; }

    private void accept(ServerSocket server, Handler handler) {
        while (!closed) {
            try {
                Socket socket = server.accept();
                sockets.add(socket);
                socket.setSoTimeout(4000);
                threads.submit(() -> {
                    try (socket) { handler.handle(socket); }
                    catch (Exception ex) { if (!closed && !(ex instanceof SocketException) && !(ex instanceof SSLException)) { failures.add(ex); } }
                    finally { sockets.remove(socket); }
                });
            } catch (IOException ex) { if (!closed) { failures.add(ex); } }
        }
    }

    private void http(Socket socket, boolean encrypted) throws Exception {
        if (encrypted) {
            var tlsSocket = (SSLSocket) socket;
            tlsSocket.startHandshake();
            var session = (ExtendedSSLSession) tlsSocket.getSession();
            for (var name : session.getRequestedServerNames()) {
                if (name instanceof SNIHostName host) { serverNames.add(host.getAsciiName()); }
            }
        }
        var in = socket.getInputStream();
        var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
        StringBuilder request = new StringBuilder();
        int contentLength = 0;
        for (String line; (line = reader.readLine()) != null && !line.isEmpty();) {
            request.append(line).append('\n');
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring(15).trim());
            }
        }
        requests.add(request.toString());
        char[] body = new char[contentLength];
        int read = 0;
        while (read < contentLength) {
            int count = reader.read(body, read, contentLength - read);
            if (count < 0) { break; }
            read += count;
        }
        bodies.add(new String(body, 0, read));
        byte[] payload = responseBody;
        var out = socket.getOutputStream();
        StringBuilder headers = new StringBuilder("HTTP/1.1 " + status + " Fixture\r\nContent-Type: " + contentType
                + "\r\nContent-Length: " + payload.length + "\r\nConnection: close\r\n");
        if (retryAfter != null) { headers.append("Retry-After: ").append(retryAfter).append("\r\n"); }
        if (location != null) { headers.append("Location: ").append(location).append("\r\n"); }
        out.write(headers.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
        if (stallBody) {
            out.write(payload, 0, 1);
            out.flush();
            while (!closed && in.read() != -1) { }
        } else {
            out.write(payload);
            out.flush();
        }
    }

    private void socks(Socket socket) throws Exception {
        var in = new DataInputStream(socket.getInputStream());
        var out = socket.getOutputStream();
        if (in.readUnsignedByte() != 5) { throw new IOException("not SOCKS5"); }
        int methods = in.readUnsignedByte();
        byte[] offered = in.readNBytes(methods);
        if (!Arrays.equals(offered, new byte[]{0})) { throw new IOException("not NO_AUTH only"); }
        out.write(new byte[]{5, 0});
        out.flush();
        if (in.readUnsignedByte() != 5 || in.readUnsignedByte() != 1 || in.readUnsignedByte() != 0) { throw new IOException("not CONNECT command"); }
        int type = in.readUnsignedByte();
        if (type != 3) { throw new IOException("upstream DNS not delegated to proxy"); }
        String domain = new String(in.readNBytes(in.readUnsignedByte()), StandardCharsets.US_ASCII);
        int port = in.readUnsignedShort();
        socksRequests.add(new SocksRequest(domain, port));
        Socket remote = new Socket(InetAddress.getLoopbackAddress(), port == 80 ? plain.getLocalPort() : tls.getLocalPort());
        sockets.add(remote);
        try (remote) {
            out.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, 0, 0});
            out.flush();
            var outbound = threads.submit(() -> { pipe(socket, remote); return null; });
            pipe(remote, socket);
            outbound.get(5, TimeUnit.SECONDS);
        } finally { sockets.remove(remote); }
    }

    private static void pipe(Socket from, Socket to) throws IOException {
        var buffer = new byte[8192];
        try {
            int count;
            while ((count = from.getInputStream().read(buffer)) != -1) {
                to.getOutputStream().write(buffer, 0, count);
                to.getOutputStream().flush();
            }
        } catch (SocketException ignored) {
            // Closing either side ends the pipe.
        }
        try { to.shutdownOutput(); } catch (SocketException ignored) { }
    }

    @Override
    public void close() throws Exception {
        closed = true;
        for (var server : new ServerSocket[]{tls, plain, proxy}) {
            try { server.close(); } catch (IOException ignored) { }
        }
        for (Socket socket : sockets) {
            try { socket.close(); } catch (IOException ignored) { }
        }
        threads.shutdownNow();
        threads.awaitTermination(5, TimeUnit.SECONDS);
    }
}