package com.e2eq.framework.rest.usage;

/** OSS contract SPI for exporting usage observations to an application's telemetry or billing boundary. */
public interface UsageObserver {
    void observe(UsageObservation observation);
}
