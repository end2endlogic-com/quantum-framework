package com.e2eq.framework.config;

import io.quarkus.runtime.annotations.StaticInitSafe;
import io.smallrye.config.ConfigMapping;

@StaticInitSafe
@ConfigMapping(prefix="b2bintegrator")
public interface B2BIntegratorConfig {
    String awsRoleArn();

    String region();

    Boolean checkMigration();

}
