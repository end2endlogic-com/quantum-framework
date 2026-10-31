package com.e2eq.framework.rest.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MediaGrantTest {

    @Test
    void grantHeadersAreDefensivelyCopied() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "image/png");
        MediaUploadGrant grant = new MediaUploadGrant(
                URI.create("https://storage.example/upload"),
                Instant.now().plusSeconds(60),
                headers);

        headers.put("X-Changed", "true");

        assertTrue(grant.requiredHeaders().containsKey("Content-Type"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> grant.requiredHeaders().put("X-New", "value"));
    }

    @Test
    void grantRequiresUriAndExpiry() {
        assertThrows(
                NullPointerException.class,
                () -> new MediaAccessGrant(null, Instant.now(), Map.of()));
        assertThrows(
                NullPointerException.class,
                () -> new MediaAccessGrant(URI.create("https://storage.example/read"), null, Map.of()));
    }

    @Test
    void verifiedScanStateControlsMediaAvailability() {
        assertEquals(
                com.e2eq.framework.model.persistent.collaboration.MediaReference.Status.AVAILABLE,
                MediaReferenceService.statusFor(
                        com.e2eq.framework.model.persistent.collaboration.MediaReference.ScanStatus.CLEAN));
        assertEquals(
                com.e2eq.framework.model.persistent.collaboration.MediaReference.Status.UPLOADED,
                MediaReferenceService.statusFor(
                        com.e2eq.framework.model.persistent.collaboration.MediaReference.ScanStatus.NOT_SCANNED));
        assertEquals(
                com.e2eq.framework.model.persistent.collaboration.MediaReference.Status.QUARANTINED,
                MediaReferenceService.statusFor(
                        com.e2eq.framework.model.persistent.collaboration.MediaReference.ScanStatus.INFECTED));
    }
}
