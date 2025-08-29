package com.e2eq.framework.config;


import io.quarkus.runtime.annotations.StaticInitSafe;
import io.smallrye.config.ConfigMapping;

/**
 * Maps PostMark configuration properties to the application's configuration.
 */
@StaticInitSafe
@ConfigMapping(prefix="postmark")
public interface PostMarkConfig {
    /**
     * PostMark API key
     * @return the API key
     */
    String apiKey();

    /**
     * Default from email address
     * @return the default from email address
     */
    String defaultFromEmailAddress();

    /**
     * Default to email address
     * @return the default to email address
     */
    String defaultToEmailAddress();
}
