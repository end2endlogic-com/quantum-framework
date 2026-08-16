package com.e2eq.framework.rest.usage;

/** Contract describing the policy outcome recorded for one REST request. */
public enum UsageAdmissionDisposition {
    ADMITTED,
    WOULD_REJECT,
    REJECTED,
    BYPASSED,
    CAPACITY_BYPASSED,
    ENFORCEMENT_ERROR
}
