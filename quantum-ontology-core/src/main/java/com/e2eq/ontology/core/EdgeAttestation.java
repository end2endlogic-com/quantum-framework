package com.e2eq.ontology.core;

import java.util.Date;
import java.util.Objects;

/**
 * Cryptographic provenance attestation for an ontology relationship edge.
 * Provides verifiable evidence of provenance in federated or multi-tenant environments.
 */
public class EdgeAttestation {
    private String keyId;
    private String signature;
    private String algorithm = "Ed25519";
    private Date timestamp;

    public EdgeAttestation() {}

    public EdgeAttestation(String keyId, String signature, String algorithm, Date timestamp) {
        this.keyId = keyId;
        this.signature = signature;
        this.algorithm = algorithm != null ? algorithm : "Ed25519";
        this.timestamp = timestamp != null ? timestamp : new Date();
    }

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }

    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }

    public String getAlgorithm() { return algorithm; }
    public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }

    public Date getTimestamp() { return timestamp; }
    public void setTimestamp(Date timestamp) { this.timestamp = timestamp; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EdgeAttestation that)) return false;
        return Objects.equals(keyId, that.keyId)
                && Objects.equals(signature, that.signature)
                && Objects.equals(algorithm, that.algorithm)
                && Objects.equals(timestamp, that.timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(keyId, signature, algorithm, timestamp);
    }

    @Override
    public String toString() {
        return "EdgeAttestation{" +
                "keyId='" + keyId + '\'' +
                ", algorithm='" + algorithm + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
