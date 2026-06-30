package com.e2eq.ontology.repo;

import com.e2eq.ontology.core.EdgeRecord;

/**
 * Per-record outcome of a governed ingest (S3): the row was either {@code ACCEPTED} (placed into a
 * governed DataDomain and upserted into the {@code edges} collection) or {@code QUARANTINED} (could
 * not be placed; written to {@code ontology_edge_quarantine}, nothing written to {@code edges}).
 */
public final class EdgeIngestResult {

    public enum Status { ACCEPTED, QUARANTINED }

    private final Status status;
    private final EdgeRecord edge;
    private final String reason;

    private EdgeIngestResult(Status status, EdgeRecord edge, String reason) {
        this.status = status;
        this.edge = edge;
        this.reason = reason;
    }

    public static EdgeIngestResult accepted(EdgeRecord edge) {
        return new EdgeIngestResult(Status.ACCEPTED, edge, null);
    }

    public static EdgeIngestResult quarantined(EdgeRecord edge, String reason) {
        return new EdgeIngestResult(Status.QUARANTINED, edge, reason);
    }

    public Status getStatus() { return status; }
    public EdgeRecord getEdge() { return edge; }
    public String getReason() { return reason; }

    public boolean isAccepted() { return status == Status.ACCEPTED; }
    public boolean isQuarantined() { return status == Status.QUARANTINED; }

    @Override
    public String toString() {
        return "EdgeIngestResult{" + status + (reason != null ? ", reason=" + reason : "") + "}";
    }
}
