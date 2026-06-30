package com.e2eq.ontology.repo;

import java.util.Collections;
import java.util.List;

/**
 * Batch summary for a governed ingest (S3). Good rows persist, bad rows quarantine; a single
 * poison row never throws and never denies the rest of the tenant's batch. The summary surfaces
 * the accepted/quarantined counts plus the per-record {@link EdgeIngestResult}s so callers can
 * react to a non-zero {@code quarantinedCount} without losing the accepted rows.
 */
public final class EdgeIngestSummary {

    private final int acceptedCount;
    private final int quarantinedCount;
    private final List<EdgeIngestResult> results;

    public EdgeIngestSummary(List<EdgeIngestResult> results) {
        this.results = (results == null) ? Collections.emptyList() : Collections.unmodifiableList(results);
        int accepted = 0;
        int quarantined = 0;
        for (EdgeIngestResult r : this.results) {
            if (r.isAccepted()) accepted++;
            else if (r.isQuarantined()) quarantined++;
        }
        this.acceptedCount = accepted;
        this.quarantinedCount = quarantined;
    }

    public int getAcceptedCount() { return acceptedCount; }
    public int getQuarantinedCount() { return quarantinedCount; }
    public List<EdgeIngestResult> getResults() { return results; }

    public boolean hasQuarantines() { return quarantinedCount > 0; }

    @Override
    public String toString() {
        return "EdgeIngestSummary{accepted=" + acceptedCount + ", quarantined=" + quarantinedCount + "}";
    }
}
