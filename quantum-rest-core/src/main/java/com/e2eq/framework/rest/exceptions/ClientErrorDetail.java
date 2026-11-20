package com.e2eq.framework.rest.exceptions;

import com.e2eq.framework.util.ExceptionLoggingUtils;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * What an error response may carry beyond its typed fields. A stack trace or a driver's message
 * names internals (classes, hosts, database names) and is logged server side, never returned to
 * the client, unless {@code quantum.rest.errors.include-debug-detail=true} is set explicitly (a
 * development setting; it defaults to false).
 */
public final class ClientErrorDetail {

    /** Opt-in development setting: return stack traces and raw exception messages to clients. */
    public static final String INCLUDE_DEBUG_DETAIL = "quantum.rest.errors.include-debug-detail";

    /** The statusMessage of an unexpected (unmapped) server error when debug detail is off. */
    public static final String UNEXPECTED_ERROR_MESSAGE = "An unexpected error occurred";

    private ClientErrorDetail() {
    }

    public static boolean includeDebugDetail() {
        try {
            return ConfigProvider.getConfig()
                    .getOptionalValue(INCLUDE_DEBUG_DETAIL, Boolean.class)
                    .orElse(false);
        } catch (RuntimeException noConfig) {
            return false;
        }
    }

    /**
     * The statusMessage of a server error (5xx): the exception's own message only when debug
     * detail is on, else {@link #UNEXPECTED_ERROR_MESSAGE}. A 5xx message is whatever a library or
     * a wrapped cause said (hosts, databases, classes), so it is logged, never returned by default.
     */
    public static String serverErrorMessage(Throwable exception) {
        return includeDebugDetail() && exception != null ? exception.getMessage() : UNEXPECTED_ERROR_MESSAGE;
    }

    /** The exception's stack trace for the response's debugMessage, or null (the default). */
    public static String stackTrace(Throwable exception) {
        return includeDebugDetail() ? ExceptionLoggingUtils.getStackTrace(exception) : null;
    }
}
