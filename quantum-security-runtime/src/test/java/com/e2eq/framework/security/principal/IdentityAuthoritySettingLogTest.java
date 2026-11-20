package com.e2eq.framework.security.principal;

import org.eclipse.microprofile.config.ConfigValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The startup line names the identity-authority state and the configuration source that set it. */
class IdentityAuthoritySettingLogTest {

    @Test
    void namesTheStateAndItsSource() {
        ConfigValue on = mock(ConfigValue.class);
        when(on.getValue()).thenReturn("true");
        when(on.getSourceName()).thenReturn("EnvConfigSource");
        String line = IdentityAuthoritySettingLog.message(on);
        assertTrue(line.startsWith("quantum.auth.identity-authority=true (source: EnvConfigSource)"), line);

        ConfigValue unset = mock(ConfigValue.class);
        assertTrue(IdentityAuthoritySettingLog.message(unset).startsWith("quantum.auth.identity-authority=false (source: default)"));
        assertTrue(IdentityAuthoritySettingLog.message(null).contains("=false"));
    }
}
