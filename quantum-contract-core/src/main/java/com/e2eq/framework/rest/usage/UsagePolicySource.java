package com.e2eq.framework.rest.usage;

import java.util.Optional;

/** OSS contract SPI for resolving a policy without request-path persistence. */
public interface UsagePolicySource {
    Optional<UsagePolicy> policyFor(UsageRequest request);
}
