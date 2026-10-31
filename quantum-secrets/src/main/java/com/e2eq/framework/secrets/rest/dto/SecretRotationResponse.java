package com.e2eq.framework.secrets.rest.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for the bulk key-rotation endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@RegisterForReflection
public class SecretRotationResponse {

    /** Number of secrets successfully re-encrypted. */
    private int rotated;

    /** Number of secrets already on the active key version (skipped). */
    private int skipped;

    /** Number of secrets that failed re-encryption. */
    private int failed;

    /** The active key version that secrets were rotated to. */
    private int activeKeyVersion;
}
