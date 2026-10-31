package com.e2eq.framework.model.persistent.base;

import com.e2eq.framework.exceptions.RefNameViolationException.Code;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cases mirror platform-ux tenant-admin-ui/src/ref-name.ts so the UI and server agree.
 */
class RefNameRuleTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "TRADING_PARTNER_ADMINS", "ABC", "A1_", "OPS_TEAM_2",
            "ZXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX" // 64 chars
    })
    void acceptsCanonicalNames(String refName) {
        assertTrue(RefNameRule.check(refName).isEmpty(), refName);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void requiresAValue(String refName) {
        assertEquals(Code.REFNAME_REQUIRED, RefNameRule.check(refName).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ops_team",            // lowercase: rejected, never re-cased
            "Ops_Team",
            "tenant-admin-users",  // hyphen
            "OPS TEAM",            // space
            " OPS_TEAM",           // not trimmed
            "1OPS",                // leading digit
            "_OPS",                // leading underscore
            "AB",                  // too short
            "ZXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", // 65 chars
            "SYSTEM.COM",
            "ADMIN@SYSTEM"
    })
    void rejectsNonCanonicalNames(String refName) {
        assertEquals(Code.REFNAME_INVALID_FORMAT, RefNameRule.check(refName).orElseThrow(), refName);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "65f1c2a9b3e4d5f6a7b8c9d0",          // ObjectId
            "ABCDEF0123456789",                  // 16 hex, would otherwise pass the pattern
            "550e8400e29b41d4a716446655440000"   // dashless UUID
    })
    void rejectsGeneratedIds(String refName) {
        assertEquals(Code.REFNAME_GENERATED_ID, RefNameRule.check(refName).orElseThrow(), refName);
    }
}
