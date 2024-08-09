package com.e2eq.framework.config;


import io.quarkus.runtime.annotations.StaticInitSafe;
import io.smallrye.config.ConfigMapping;

@StaticInitSafe
@ConfigMapping(prefix="postmark")
public interface PostMarkConfig {
    String apiKey();
    String defaultFromEmailAddress();

    String defaultToEmailAddress();
}
