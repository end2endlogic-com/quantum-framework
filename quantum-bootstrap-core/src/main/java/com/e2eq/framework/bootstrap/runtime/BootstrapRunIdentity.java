package com.e2eq.framework.bootstrap.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Canonical serialized snapshots at the bootstrap persistence boundary. */
final class BootstrapRunIdentity {
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("BOOTSTRAP_PLAN_ENCODING_FAILED",e); }
    }
    static <T> T copy(T value,Class<T> type) {
        try { return JSON.readValue(json(value),type); }
        catch(Exception e) { throw new IllegalArgumentException("BOOTSTRAP_PLAN_INVALID",e); }
    }
    static String fingerprint(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(json(value).getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalArgumentException("BOOTSTRAP_REQUEST_HASH_FAILED",e); }
    }
}
