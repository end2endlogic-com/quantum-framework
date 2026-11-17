package com.e2eq.framework.security.principal;

import io.quarkus.logging.Log;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;

import java.util.Objects;

/**
 * Runs a credential's {@code impersonateFilterScript} in a sandboxed GraalJS context and answers
 * whether it permits the identity to impersonate (or delegate) in a realm. Moved unchanged from
 * {@code SecurityFilter.runScript}: runtime configuration
 * ({@code quantum.security.scripting.*}) wins; the constructor values apply only when no
 * configuration is available.
 */
public class ImpersonationScriptEvaluator {

    private static final java.util.concurrent.atomic.AtomicBoolean WARNED_PERMISSIVE =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private final boolean scriptingEnabled;
    private final boolean scriptingAllowAllAccess;
    private final long scriptingTimeoutMillis;
    private final long scriptingMaxStatements;

    public ImpersonationScriptEvaluator() {
        this(true, false, 250L, 10000L);
    }

    public ImpersonationScriptEvaluator(boolean scriptingEnabled, boolean scriptingAllowAllAccess,
                                        long scriptingTimeoutMillis, long scriptingMaxStatements) {
        this.scriptingEnabled = scriptingEnabled;
        this.scriptingAllowAllAccess = scriptingAllowAllAccess;
        this.scriptingTimeoutMillis = scriptingTimeoutMillis;
        this.scriptingMaxStatements = scriptingMaxStatements;
    }

    public boolean evaluate(String subject, String userId, String realm, String script) {
        Objects.requireNonNull(subject, "subject cannot be null");
        Objects.requireNonNull(userId, "UserId cannot be null");
        Objects.requireNonNull(realm, "Realm cannot be null");
        Objects.requireNonNull(script, "Script cannot be null");

        Log.debugf("Running impersonation filter script for user:%s, userId:%s, realm:%s", subject, userId, realm);

        // Resolve scripting config with runtime fallback (when not CDI-injected)
        boolean enabled = scriptingEnabled;
        boolean allowAll = scriptingAllowAllAccess;
        long timeoutMs = scriptingTimeoutMillis;
        long maxStatementsValue = scriptingMaxStatements;
        try {
            org.eclipse.microprofile.config.Config cfg = org.eclipse.microprofile.config.ConfigProvider.getConfig();
            if (cfg != null) {
                enabled = cfg.getOptionalValue("quantum.security.scripting.enabled", Boolean.class).orElse(Boolean.TRUE);
                allowAll = cfg.getOptionalValue("quantum.security.scripting.allowAllAccess", Boolean.class).orElse(Boolean.FALSE);
                timeoutMs = cfg.getOptionalValue("quantum.security.scripting.timeout.millis", Long.class).orElse(1500L);
                maxStatementsValue = cfg.getOptionalValue("quantum.security.scripting.maxStatements", Long.class).orElse(10000L);
            }
        } catch (Throwable ignored) {
            if (timeoutMs <= 0) timeoutMs = 1500L;
            if (maxStatementsValue <= 0) maxStatementsValue = 10000L;
        }
        if (timeoutMs < 500L) timeoutMs = 1500L;
        // Make final for use in lambda
        final long maxStatements = maxStatementsValue;

        if (!enabled) {
            Log.warn("Security scripting is disabled via config; returning false");
            return false;
        }

        // Permissive mode: Only allow in development/test environments via system property
        if (allowAll) {
            String permissiveEnv = System.getProperty("quantum.security.scripting.allowPermissiveEnv", "false");
            if (!"true".equals(permissiveEnv) && !"dev".equals(permissiveEnv) && !"test".equals(permissiveEnv)) {
                Log.error("Permissive script mode requested but not allowed in this environment. Set system property quantum.security.scripting.allowPermissiveEnv=true to enable (UNSAFE).");
                throw new SecurityException("Permissive script execution not allowed in this environment");
            }
            if (WARNED_PERMISSIVE.compareAndSet(false, true)) {
                Log.warn("Permissive script mode enabled - UNSAFE - only for development/testing. This should never be used in production.");
            }
            try (Context c = Context.newBuilder("js").allowAllAccess(true).build()) {
                c.getBindings("js").putMember("subject", subject);
                c.getBindings("js").putMember("userId", userId);
                c.getBindings("js").putMember("realm", realm);
                Value v = c.eval("js", script);
                return v.isBoolean() ? v.asBoolean() : false;
            } catch (Throwable t) {
                Log.warn("Script execution failed in permissive mode; returning false", t);
                return false;
            }
        }

        // Hardened mode with timeout and resource limits
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread th = new Thread(r, "impersonation-script-worker");
            th.setDaemon(true);
            return th;
        });
        try {
            java.util.concurrent.Future<Boolean> fut = executor.submit(() -> {
                Engine eng = Engine.newBuilder().build();
                try (Context c = Context.newBuilder("js")
                        .engine(eng)
                        .allowAllAccess(false)
                        // Enhanced security: disable public access, only allow array/list access for bindings
                        .allowHostAccess(HostAccess.newBuilder()
                                .allowPublicAccess(false)  // Disable public access
                                .allowArrayAccess(true)    // Only allow array access for bindings
                                .allowListAccess(true)     // Only allow list access for bindings
                                .build())
                        .allowHostClassLookup(s -> false)  // Disable class lookup
                        .allowIO(false)                     // Disable I/O
                        .allowNativeAccess(false)           // Disable native access
                        .allowCreateThread(false)           // Disable thread creation
                        .allowCreateProcess(false)          // Disable process creation
                        .option("js.ecmascript-version", "2021")
                        .build()) {
                    c.getBindings("js").putMember("subject", subject);
                    c.getBindings("js").putMember("userId", userId);
                    c.getBindings("js").putMember("realm", realm);
                    Value v = c.eval("js", script);
                    return v.isBoolean() ? v.asBoolean() : false;
                }
            });
            Boolean result = fut.get(Math.max(1L, timeoutMs), java.util.concurrent.TimeUnit.MILLISECONDS);
            return result;
        } catch (java.util.concurrent.TimeoutException te) {
            Log.warnf("Impersonation script timed out after %d ms; returning false", (timeoutMs <= 0 ? 1500L : timeoutMs));
            return false;
        } catch (Throwable t) {
            Log.warn("Impersonation script execution failed; returning false", t);
            return false;
        } finally {
            executor.shutdownNow();
        }

    }
}
