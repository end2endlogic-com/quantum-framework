package com.e2eq.framework.security;

import com.e2eq.framework.securityrules.RuleContext;
import io.quarkus.logging.Log;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Test-scoped helper for interacting with RuleContext in Quarkus tests.
 * Provides explicit reload and simple wrappers that are safe if CDI is not available.
 */
public final class RuleContextTestUtils {

    private RuleContextTestUtils() {}

    /**
     * Attempts to obtain a CDI-managed RuleContext. Returns empty if not running under CDI.
     */
    public static Optional<RuleContext> getRuleContext() {
        try {
            Instance<RuleContext> inst = CDI.current().select(RuleContext.class);
            if (inst.isResolvable()) {
                return Optional.ofNullable(inst.get());
            }
        } catch (Throwable t) {
            Log.debug("RuleContextTestUtils: CDI not available for RuleContext", t);
        }
        return Optional.empty();
    }

    /**
     * Explicitly reloads policies for the given realm using the CDI-managed RuleContext when available.
     * If RuleContext is not available, this method is a no-op.
     */
    public static void reload(String realm) {
        getRuleContext().ifPresent(rc -> {
            try {
                rc.reloadFromRepo(realm);
            } catch (Throwable t) {
                Log.warnf(t, "RuleContextTestUtils.reload failed for realm %s", realm);
            }
        });
    }

    /**
     * Runs a block ensuring RuleContext is reloaded first; returns the supplier's value.
     */
    public static <T> T withReloadFor(String realm, Supplier<T> supplier) {
        reload(realm);
        return supplier.get();
    }

    /**
     * Runs a block ensuring RuleContext is reloaded first.
     */
    public static void withReloadFor(String realm, Runnable runnable) {
        reload(realm);
        runnable.run();
    }
}
