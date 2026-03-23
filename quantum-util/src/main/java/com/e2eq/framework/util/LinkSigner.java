package com.e2eq.framework.util;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

public final class LinkSigner {
    private static final Logger LOG = Logger.getLogger(LinkSigner.class);
    private static final String ALG = "HmacSHA256";
    private static final String CONF_KEY = "share.link.signing.secret";
    private static final long DEFAULT_MAX_SKEW_SECONDS = 86400L;

    private LinkSigner() {}

    public static boolean isEnabled() {
        try {
            Config cfg = ConfigProvider.getConfig();
            String secret = cfg.getOptionalValue(CONF_KEY, String.class).orElse(null);
            return secret != null && !secret.isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    public static String sign(String publicId, long tsSeconds) {
        String secret = getSecret();
        if (secret == null || secret.isBlank()) return null;
        String payload = publicId + ":" + tsSeconds;
        byte[] mac = hmac(secret, payload);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac);
    }

    public static boolean verify(String publicId, long tsSeconds, String sig) {
        return verify(publicId, tsSeconds, sig, DEFAULT_MAX_SKEW_SECONDS);
    }

    public static boolean verify(String publicId, long tsSeconds, String sig, long maxSkewSeconds) {
        String secret = getSecret();
        if (secret == null || secret.isBlank()) return false;
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - tsSeconds) > maxSkewSeconds) {
            return false;
        }
        String expected = sign(publicId, tsSeconds);
        if (expected == null || sig == null) return false;
        return constantTimeEquals(expected, sig);
    }

    private static String getSecret() {
        try {
            Config cfg = ConfigProvider.getConfig();
            return cfg.getOptionalValue(CONF_KEY, String.class).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] hmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(ALG);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALG));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            LOG.warn("Failed to compute HMAC", e);
            return new byte[0];
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] aa = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        if (aa.length != bb.length) return false;
        int result = 0;
        for (int i = 0; i < aa.length; i++) {
            result |= aa[i] ^ bb[i];
        }
        return result == 0;
    }
}
