package com.e2eq.framework.rest.ratelimit;

import java.net.InetAddress;
import java.util.Arrays;

/** Immutable IP/CIDR matcher used to bind forwarded headers to trusted peers. */
final class TrustedPeerNetwork {

    private final byte[] network;
    private final int prefixLength;

    private TrustedPeerNetwork(byte[] network, int prefixLength) {
        this.network = network;
        this.prefixLength = prefixLength;
    }

    static TrustedPeerNetwork parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("trusted peer entry is blank");
        }
        String[] parts = expression.split("/", -1);
        if (parts.length > 2) {
            throw new IllegalArgumentException("trusted peer entry has more than one prefix separator");
        }
        InetAddress address = RateLimitClientIdentity.parseIpLiteral(parts[0]);
        if (address == null) {
            throw new IllegalArgumentException("trusted peer address is not an IP literal");
        }
        byte[] bytes = address.getAddress();
        int maximumPrefix = bytes.length * Byte.SIZE;
        int prefix = maximumPrefix;
        if (parts.length == 2) {
            try {
                prefix = Integer.parseInt(parts[1]);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("trusted peer prefix is not an integer", exception);
            }
        }
        if (prefix < 0 || prefix > maximumPrefix) {
            throw new IllegalArgumentException("trusted peer prefix is outside the address family range");
        }
        maskHostBits(bytes, prefix);
        return new TrustedPeerNetwork(bytes, prefix);
    }

    boolean contains(String normalizedAddress) {
        InetAddress parsed = RateLimitClientIdentity.parseIpLiteral(normalizedAddress);
        if (parsed == null) {
            return false;
        }
        byte[] candidate = parsed.getAddress();
        if (candidate.length != network.length) {
            return false;
        }
        maskHostBits(candidate, prefixLength);
        return Arrays.equals(network, candidate);
    }

    static void maskHostBits(byte[] address, int prefixLength) {
        int fullBytes = prefixLength / Byte.SIZE;
        int remainingBits = prefixLength % Byte.SIZE;
        if (remainingBits != 0 && fullBytes < address.length) {
            int mask = 0xff << (Byte.SIZE - remainingBits);
            address[fullBytes] = (byte) (address[fullBytes] & mask);
            fullBytes++;
        }
        Arrays.fill(address, fullBytes, address.length, (byte) 0);
    }
}
