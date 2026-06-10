package com.e2eq.framework.system;

import com.e2eq.framework.api.system.QuantumMode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Plain unit test — no Quarkus boot needed for the mode parsing contract. */
public class TestQuantumModeParsing {

    @Test
    public void parsesKnownModes() {
        Assertions.assertEquals(QuantumMode.EMBEDDED, QuantumMode.fromConfig("embedded"));
        Assertions.assertEquals(QuantumMode.REMOTE, QuantumMode.fromConfig("remote"));
        Assertions.assertEquals(QuantumMode.REMOTE, QuantumMode.fromConfig("  Remote  "));
    }

    @Test
    public void blankDefaultsToEmbedded() {
        Assertions.assertEquals(QuantumMode.EMBEDDED, QuantumMode.fromConfig(null));
        Assertions.assertEquals(QuantumMode.EMBEDDED, QuantumMode.fromConfig("   "));
    }

    @Test
    public void unknownModeFailsLoud() {
        IllegalStateException failure = Assertions.assertThrows(IllegalStateException.class,
            () -> QuantumMode.fromConfig("hybrid"));
        Assertions.assertTrue(failure.getMessage().contains("quantum.mode"));
    }
}
