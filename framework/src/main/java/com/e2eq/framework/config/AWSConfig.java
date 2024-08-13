package com.e2eq.framework.config;

import io.quarkus.runtime.annotations.StaticInitSafe;
import io.smallrye.config.ConfigMapping;

import java.util.Optional;

@StaticInitSafe
@ConfigMapping(prefix="awsconfig")
public interface AWSConfig {
    Optional<String> awsRoleArn();

    Optional<String> region();

    Optional<Boolean> checkMigration();

}
