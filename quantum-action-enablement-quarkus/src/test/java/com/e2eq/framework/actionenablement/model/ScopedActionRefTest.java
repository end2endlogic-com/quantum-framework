package com.e2eq.framework.actionenablement.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScopedActionRefTest {

    @Test
    void toUriStringNormalizesEachSegment() {
        ScopedActionRef ref = ScopedActionRef.builder()
                .area(" System ")
                .functionalDomain(" Action-Enablement ")
                .action(" Check ")
                .build();

        assertEquals("system/action-enablement/check", ref.toUriString());
    }

    @Test
    void nullSegmentsNormalizeToEmptyStrings() {
        ScopedActionRef ref = new ScopedActionRef();

        assertEquals("", ref.normalizedArea());
        assertEquals("", ref.normalizedFunctionalDomain());
        assertEquals("", ref.normalizedAction());
        assertEquals("//", ref.toUriString());
    }
}
