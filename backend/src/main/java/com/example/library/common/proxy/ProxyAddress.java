package com.example.library.common.proxy;

import java.net.IDN;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

final class ProxyAddress {
    private ProxyAddress() { }

    static String normalize(String raw) {
        if (raw == null) { return null; }
        if (raw.chars().anyMatch(Character::isISOControl)) { throw new IllegalArgumentException("invalid proxy host"); }
        if (raw.trim().isEmpty()) { return null; }
        String host = raw.trim();
        if (host.chars().anyMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c))
                || host.matches(".*[/@?#\\\\%*\\[\\]].*")) {
            throw new IllegalArgumentException("invalid proxy host");
        }
        if (host.contains(":")) {
            if (!host.matches("[0-9a-fA-F:.]+") || host.chars().filter(c -> c == ':').count() < 2) {
                throw new IllegalArgumentException("invalid IPv6 literal");
            }
            try { InetAddress.getByName(host); }
            catch (UnknownHostException ex) { throw new IllegalArgumentException("invalid IPv6 literal"); }
            return host.toLowerCase(Locale.ROOT);
        }
        if (host.matches("[0-9.]+") && host.contains(".")) {
            String[] parts = host.split("\\.", -1);
            if (parts.length != 4) { throw new IllegalArgumentException("invalid IPv4 literal"); }
            for (String part : parts) {
                if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255) {
                    throw new IllegalArgumentException("invalid IPv4 literal");
                }
            }
            return host;
        }
        String ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        if (ascii.length() > 253 || ascii.isEmpty()) { throw new IllegalArgumentException("invalid DNS name"); }
        for (String label : ascii.split("\\.", -1)) {
            if (label.length() > 63 || !label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) {
                throw new IllegalArgumentException("invalid DNS label");
            }
        }
        return ascii;
    }
}
