package com.e2eq.framework.config;


import io.quarkus.runtime.annotations.StaticInitSafe;
import io.smallrye.config.ConfigMapping;

import java.util.Optional;

/**
 * Maps PostMark configuration properties to the application's configuration.
 * All properties are Optional so that Quarkus does not reject missing values at startup.
 * Validation is deferred to execution time — callers should check presence before use.
 */
@StaticInitSafe
@ConfigMapping(prefix="postmark")
public interface PostMarkConfig {
    /**
     * PostMark API key
     * @return the API key, if configured
     */
    Optional<String> apiKey();

    /**
     * Default from email address
     * @return the default from email address, if configured
     */
    Optional<String> defaultFromEmailAddress();

    /**
     * Default to email address
     * @return the default to email address, if configured
     */
    Optional<String> defaultToEmailAddress();
}
