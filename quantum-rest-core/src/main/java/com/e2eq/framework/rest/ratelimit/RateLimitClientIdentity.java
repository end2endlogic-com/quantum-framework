package com.e2eq.framework.rest.ratelimit;

import jakarta.enterprise.context.ApplicationScoped;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;

/** Resolves a bounded, trusted bucket key for one request. */
@ApplicationScoped
public class RateLimitClientIdentity {

    static final String ANONYMOUS = "anonymous";
    private static final int MAX_FORWARDED_HEADER_LENGTH = 2_048;
    private static final Pattern NUMERIC_IP = Pattern.compile("[0-9a-fA-F:.]+");

    Resolution resolve(
            String forwardedFor,
            String peerAddress,
            RateLimitConfig config) {
        String normalizedPeer = normalizeIpLiteral(peerAddress);
        if (config.forwardedEnabled()) {
            if (config.isTrustedForwardedPeer(normalizedPeer)) {
                String forwardedAddress = trustedForwardedAddress(forwardedFor, config.trustedProxyHops());
                if (forwardedAddress != null) {
                    return new Resolution(
                            "forwarded:" + networkIdentity(forwardedAddress, config),
                            false,
                            observedHops(forwardedFor));
                }
                return new Resolution(null, true, observedHops(forwardedFor));
            }
        }

        if (normalizedPeer != null) {
            return new Resolution("peer:" + normalizedPeer, false, observedHops(forwardedFor));
        }
        return new Resolution(ANONYMOUS, false, observedHops(forwardedFor));
    }

    private static String trustedForwardedAddress(String header, int trustedProxyHops) {
        if (header == null || header.isBlank() || header.length() > MAX_FORWARDED_HEADER_LENGTH) {
            return null;
        }
        String[] hops = header.split(",", -1);
        if (hops.length < trustedProxyHops) {
            return null;
        }
        int clientIndex = hops.length - trustedProxyHops;
        return normalizeIpLiteral(hops[clientIndex]);
    }

    private static int observedHops(String header) {
        if (header == null || header.isBlank() || header.length() > MAX_FORWARDED_HEADER_LENGTH) {
            return 0;
        }
        return header.split(",", -1).length;
    }

    static String normalizeIpLiteral(String value) {
        InetAddress parsed = parseIpLiteral(value);
        return parsed == null ? null : parsed.getHostAddress().toLowerCase(Locale.ROOT);
    }

    static InetAddress parseIpLiteral(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.trim();
        if (candidate.startsWith("[")) {
            int closeBracket = candidate.indexOf(']');
            if (closeBracket < 0 || (closeBracket + 1 < candidate.length()
                    && !validPortSuffix(candidate.substring(closeBracket + 1)))) {
                return null;
            }
            candidate = candidate.substring(1, closeBracket);
        } else if (candidate.indexOf('.') >= 0 && count(candidate, ':') == 1) {
            int separator = candidate.lastIndexOf(':');
            if (!validPortSuffix(candidate.substring(separator))) {
                return null;
            }
            candidate = candidate.substring(0, separator);
        }
        int zoneIndex = candidate.indexOf('%');
        if (zoneIndex >= 0) {
            candidate = candidate.substring(0, zoneIndex);
        }
        if (candidate.isEmpty() || candidate.length() > 64 || !NUMERIC_IP.matcher(candidate).matches()) {
            return null;
        }
        String lowered = candidate.toLowerCase(Locale.ROOT);
        if (lowered.startsWith("::ffff:")) {
            InetAddress mapped = parseIpLiteral(candidate.substring("::ffff:".length()));
            return mapped != null && mapped.getAddress().length == 4 ? mapped : null;
        }
        if (candidate.indexOf(':') >= 0) {
            if (!isStructurallyValidIpv6Literal(candidate)) {
                return null;
            }
            try {
                InetAddress parsed = InetAddress.getByName(candidate);
                if (!(parsed instanceof Inet6Address)) {
                    return null;
                }
                return parsed;
            } catch (UnknownHostException exception) {
                return null;
            }
        }

        String[] octets = candidate.split("\\.", -1);
        if (octets.length != 4) {
            return null;
        }
        byte[] bytes = new byte[4];
        int index = 0;
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) {
                return null;
            }
            int parsed;
            try {
                parsed = Integer.parseInt(octet);
            } catch (NumberFormatException exception) {
                return null;
            }
            if (parsed < 0 || parsed > 255) {
                return null;
            }
            bytes[index++] = (byte) parsed;
        }
        try {
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException exception) {
            return null;
        }
    }

    private static String networkIdentity(String normalizedAddress, RateLimitConfig config) {
        InetAddress parsed = parseIpLiteral(normalizedAddress);
        if (parsed == null) {
            throw new IllegalStateException("normalized forwarded address could not be parsed");
        }
        byte[] bytes = parsed.getAddress();
        int prefix = config.forwardedPrefixLength(bytes.length == 16);
        TrustedPeerNetwork.maskHostBits(bytes, prefix);
        try {
            return InetAddress.getByAddress(bytes).getHostAddress().toLowerCase(Locale.ROOT) + "/" + prefix;
        } catch (UnknownHostException exception) {
            throw new IllegalStateException("failed to create normalized forwarded network", exception);
        }
    }

    private static boolean validPortSuffix(String suffix) {
        if (suffix == null || suffix.length() < 2 || suffix.charAt(0) != ':') {
            return false;
        }
        try {
            int port = Integer.parseInt(suffix.substring(1));
            return port >= 0 && port <= 65_535;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static long count(String value, char character) {
        return value.chars().filter(candidate -> candidate == character).count();
    }

    /**
     * Reject malformed values before calling {@link InetAddress#getByName(String)}.
     * A structurally valid value contains only numeric IPv6 groups, so the JDK
     * parser cannot interpret attacker-controlled input as a DNS name.
     */
    private static boolean isStructurallyValidIpv6Literal(String candidate) {
        if (candidate.indexOf('.') >= 0) {
            return false;
        }
        int compression = candidate.indexOf("::");
        if (compression != candidate.lastIndexOf("::")) {
            return false;
        }
        if ((candidate.startsWith(":") && !candidate.startsWith("::"))
                || (candidate.endsWith(":") && !candidate.endsWith("::"))) {
            return false;
        }

        String[] groups = candidate.split(":", -1);
        int nonEmptyGroups = 0;
        for (String group : groups) {
            if (group.isEmpty()) {
                continue;
            }
            if (group.length() > 4) {
                return false;
            }
            nonEmptyGroups++;
        }
        return compression >= 0 ? nonEmptyGroups < 8 : nonEmptyGroups == 8;
    }

    record Resolution(String key, boolean forwardedResolutionFailed, int observedForwardedHops) {
    }
}
