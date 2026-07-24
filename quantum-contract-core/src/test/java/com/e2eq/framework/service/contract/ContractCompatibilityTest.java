package com.e2eq.framework.service.contract;

import com.e2eq.framework.service.contract.ContractCompatibility.Result;
import com.e2eq.framework.service.contract.ContractCompatibility.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractCompatibilityTest {

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Test
    void identicalHashShortCircuits_versionsNotConsulted() {
        Result r = ContractCompatibility.evaluate(HASH_A, "garbage", HASH_A, null);
        assertEquals(Verdict.IDENTICAL, r.verdict());
        assertTrue(r.allowed());
    }

    @Test
    void patchDifference_isCompatible() {
        Result r = ContractCompatibility.evaluate(HASH_A, "1.2.1", HASH_B, "1.2.2");
        assertEquals(Verdict.COMPATIBLE, r.verdict());
        assertTrue(r.allowed());
    }

    @Test
    void serverMinorNewer_isCompatible_clientRangeSemantics() {
        // Client 1.2.2 declares [1.2, 2.0) on the server: 1.3.2 satisfies it.
        Result r = ContractCompatibility.evaluate(HASH_A, "1.2.2", HASH_B, "1.3.2");
        assertEquals(Verdict.COMPATIBLE, r.verdict());
    }

    @Test
    void serverMinorOlder_failsDirectionally() {
        // Client 1.3.2 requires at least a 1.3.x service.
        Result r = ContractCompatibility.evaluate(HASH_A, "1.3.2", HASH_B, "1.2.9");
        assertEquals(Verdict.SERVICE_OLDER, r.verdict());
        assertFalse(r.allowed());
        assertTrue(r.message().contains("OLDER"));
    }

    @Test
    void majorDifference_failsBothDirections() {
        assertEquals(Verdict.INCOMPATIBLE,
            ContractCompatibility.evaluate(HASH_A, "1.9.0", HASH_B, "2.0.0").verdict());
        assertEquals(Verdict.INCOMPATIBLE,
            ContractCompatibility.evaluate(HASH_A, "2.0.0", HASH_B, "1.9.0").verdict());
    }

    @Test
    void mavenStyleQualifiersAreIgnored() {
        Result r = ContractCompatibility.evaluate(HASH_A, "1.4.0-SNAPSHOT", HASH_B, "1.4.0");
        assertEquals(Verdict.COMPATIBLE, r.verdict());
        assertEquals(Verdict.COMPATIBLE,
            ContractCompatibility.evaluate(HASH_A, "1.4.0", HASH_B, "1.5.0-SNAPSHOT").verdict());
    }

    @Test
    void differingHashWithUnparseableVersion_failsClosed() {
        // The hash is the fact, the version is the promise: no comparable
        // promise + differing fact = incompatible.
        Result r = ContractCompatibility.evaluate(HASH_A, "not-a-version", HASH_B, "1.2.3");
        assertEquals(Verdict.INCOMPATIBLE, r.verdict());
        Result missing = ContractCompatibility.evaluate(HASH_A, "1.2.3", HASH_B, null);
        assertEquals(Verdict.INCOMPATIBLE, missing.verdict());
    }

    @Test
    void twoSegmentVersionsParse() {
        Result r = ContractCompatibility.evaluate(HASH_A, "1.2", HASH_B, "1.2.5");
        assertEquals(Verdict.COMPATIBLE, r.verdict());
    }
}
