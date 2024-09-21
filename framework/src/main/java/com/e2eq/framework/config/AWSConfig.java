package com.e2eq.framework.config;

import io.quarkus.runtime.annotations.StaticInitSafe;
import io.smallrye.config.ConfigMapping;

import java.util.Optional;

/**
 * Maps AWS configuration properties to the application's configuration.'
 */
@StaticInitSafe
@ConfigMapping(prefix="awsconfig")
public interface AWSConfig {
    /**
     *  AWS role ARN
     * @return the role ARN
     */
    Optional<String> awsRoleArn();

    /**
     * AWS region
     * @return the region
     */
    Optional<String> region();

    /**
     * Migration flag
     * @return the migration flag
     */
    Optional<Boolean> checkMigration();

}
