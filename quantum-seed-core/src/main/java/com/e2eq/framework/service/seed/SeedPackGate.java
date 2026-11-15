package com.e2eq.framework.service.seed;

import java.util.List;
import java.util.Map;

/**
 * Admits or refuses a seed pack before any dataset of the apply is written. {@link SeedLoader}
 * plans every requested pack (includes first), runs each gate over each pack, and writes only when
 * every gate admitted every pack, so a refusal leaves the realm as it was.
 */
@FunctionalInterface
public interface SeedPackGate {

    /**
     * @param descriptor the pack
     * @param context    the realm and tenant being seeded
     * @param records    the pack's dataset records as they would be persisted (transforms applied)
     * @throws SeedLoadingException to refuse the pack
     */
    void admit(SeedPackDescriptor descriptor, SeedContext context, DatasetRecords records);

    /** Reads one dataset of the pack on demand. */
    @FunctionalInterface
    interface DatasetRecords {
        List<Map<String, Object>> read(SeedPackManifest.Dataset dataset);
    }
}
