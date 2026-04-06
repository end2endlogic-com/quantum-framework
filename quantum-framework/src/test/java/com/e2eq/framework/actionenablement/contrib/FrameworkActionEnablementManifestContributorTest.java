package com.e2eq.framework.actionenablement.contrib;

import com.e2eq.framework.actionenablement.model.ScopedActionRequirement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrameworkActionEnablementManifestContributorTest {

    private final FrameworkActionEnablementManifestContributor contributor = new FrameworkActionEnablementManifestContributor();

    @Test
    void exposesFrameworkActionEnablementManifestEntries() {
        List<ScopedActionRequirement> requirements = contributor.requirements().stream().toList();

        assertEquals(2, requirements.size());
        assertEquals("system/action-enablement/check", requirements.get(0).getScopedAction().toUriString());
        assertEquals("permission", requirements.get(0).getDependencies().get(0).normalizedType());
        assertEquals("system/action-enablement/view", requirements.get(1).getScopedAction().toUriString());
        assertEquals("permission", requirements.get(1).getDependencies().get(0).normalizedType());
    }
}
