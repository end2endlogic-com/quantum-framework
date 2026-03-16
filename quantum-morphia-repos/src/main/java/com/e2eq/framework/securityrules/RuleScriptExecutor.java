package com.e2eq.framework.securityrules;

import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import io.quarkus.logging.Log;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

final class RuleScriptExecutor {

    private static final AtomicBoolean WARNED_PERMISSIVE = new AtomicBoolean(false);

    private final LabelService labelService;

    RuleScriptExecutor(LabelService labelService) {
        this.labelService = labelService;
    }

    boolean runScript(long configuredTimeoutMillis, PrincipalContext pcontext, ResourceContext rcontext, String script) {
        if (script == null || script.isBlank()) {
            return false;
        }

        boolean enabled = true;
        boolean allowAll = false;
        long timeoutMs = 1500L;
        if (configuredTimeoutMillis > 0) {
            timeoutMs = configuredTimeoutMillis;
        }

        try {
            Config cfg = ConfigProvider.getConfig();
            if (cfg != null) {
                enabled = cfg.getOptionalValue("quantum.security.scripting.enabled", Boolean.class).orElse(Boolean.TRUE);
                allowAll = cfg.getOptionalValue("quantum.security.scripting.allowAllAccess", Boolean.class).orElse(Boolean.FALSE);
                timeoutMs = cfg.getOptionalValue("quantum.security.scripting.timeout.millis", Long.class).orElse(timeoutMs);
            }
        } catch (Throwable ignored) {
            if (timeoutMs <= 0) {
                timeoutMs = 1500L;
            }
        }

        if (timeoutMs < 500L) {
            timeoutMs = 1500L;
        }

        if (!enabled) {
            Log.warn("Security scripting is disabled via config; returning false");
            return false;
        }

        if (allowAll) {
            if (WARNED_PERMISSIVE.compareAndSet(false, true)) {
                Log.warn("quantum.security.scripting.allowAllAccess=true - running scripts with full host access (UNSAFE). This should only be used for compatibility.");
            }
            try (Context context = Context.newBuilder("js").allowAllAccess(true).build()) {
                var jsBindings = context.getBindings("js");
                jsBindings.putMember("pcontext", pcontext);
                jsBindings.putMember("rcontext", rcontext);
                installHelpersAndBindings(context, pcontext, rcontext);
                if (Log.isDebugEnabled()) {
                    Log.debugf("Executing script (permissive): %s", script);
                }
                return context.eval("js", script).asBoolean();
            } catch (Throwable t) {
                Log.warn("Script execution failed in permissive mode; returning false", t);
                return false;
            }
        }

        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "rule-script-worker");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<Boolean> future = executor.submit(() -> {
                Engine engine = Engine.newBuilder().build();
                try (Context context = Context.newBuilder("js")
                        .engine(engine)
                        .allowAllAccess(false)
                        .allowHostAccess(HostAccess.newBuilder().allowPublicAccess(true).build())
                        .allowHostClassLookup(className -> false)
                        .allowIO(false)
                        .option("js.ecmascript-version", "2021")
                        .build()) {
                    var jsBindings = context.getBindings("js");
                    jsBindings.putMember("pcontext", pcontext);
                    jsBindings.putMember("rcontext", rcontext);
                    installHelpersAndBindings(context, pcontext, rcontext);
                    if (Log.isDebugEnabled()) {
                        Log.debugf("Executing script: %s", script);
                    }
                    Value value = context.eval("js", script);
                    return value.isBoolean() && value.asBoolean();
                }
            });
            return future.get(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeoutException) {
            Log.warnf("Script timed out after %d ms; returning false", timeoutMs <= 0 ? 250L : timeoutMs);
            return false;
        } catch (Throwable t) {
            Log.warn("Script execution failed; returning false", t);
            return false;
        } finally {
            executor.shutdownNow();
        }
    }

    private void installHelpersAndBindings(Context context, PrincipalContext pcontext, ResourceContext rcontext) {
        var jsBindings = context.getBindings("js");

        Map<String, Object> helperBindings = new HashMap<>();
        Map<String, Object> resourceBindings = new HashMap<>();
        Map<String, Object> principalBindings = new HashMap<>();
        Map<String, Object> identityInfo = new HashMap<>();

        try {
            Set<String> resourceLabels = labelService != null ? labelService.labelsFor(rcontext) : Set.of();
            resourceBindings.put("labels", new ArrayList<>(resourceLabels));
        } catch (Throwable ignored) {
        }
        try {
            Set<String> principalLabels = labelService != null ? labelService.labelsFor(pcontext) : Set.of();
            principalBindings.put("labels", new ArrayList<>(principalLabels));
        } catch (Throwable ignored) {
        }
        helperBindings.put("rcontext", resourceBindings);
        helperBindings.put("pcontext", principalBindings);

        try {
            if (pcontext != null) {
                identityInfo.put("userId", pcontext.getUserId());
                Object rolesObj = pcontext.getRoles();
                ArrayList<String> roles = new ArrayList<>();
                if (rolesObj instanceof java.util.Collection<?> roleCollection) {
                    for (Object role : roleCollection) {
                        if (role != null) {
                            roles.add(String.valueOf(role));
                        }
                    }
                } else if (rolesObj != null && rolesObj.getClass().isArray()) {
                    int len = java.lang.reflect.Array.getLength(rolesObj);
                    for (int i = 0; i < len; i++) {
                        Object role = java.lang.reflect.Array.get(rolesObj, i);
                        if (role != null) {
                            roles.add(String.valueOf(role));
                        }
                    }
                }
                identityInfo.put("roles", roles);
                identityInfo.put("currentIdentity", pcontext.getUserId());
            }
        } catch (Throwable ignored) {
        }

        try {
            try {
                Class<?> cls = Class.forName("com.e2eq.ontology.policy.ScriptHelpers");
                java.lang.reflect.Method installMethod = cls.getMethod("install", Map.class);
                installMethod.invoke(null, helperBindings);
            } catch (Throwable ignored) {
            }
            Object isA = helperBindings.get("isA");
            if (isA != null) {
                jsBindings.putMember("isA", isA);
            }
            Object hasLabel = helperBindings.get("hasLabel");
            if (hasLabel != null) {
                jsBindings.putMember("hasLabel", hasLabel);
            }
            Object hasEdge = helperBindings.get("hasEdge");
            if (hasEdge != null) {
                jsBindings.putMember("hasEdge", hasEdge);
            }
            Object hasAnyEdge = helperBindings.get("hasAnyEdge");
            if (hasAnyEdge != null) {
                jsBindings.putMember("hasAnyEdge", hasAnyEdge);
            }
            Object hasAllEdges = helperBindings.get("hasAllEdges");
            if (hasAllEdges != null) {
                jsBindings.putMember("hasAllEdges", hasAllEdges);
            }
            Object relatedIds = helperBindings.get("relatedIds");
            if (relatedIds != null) {
                jsBindings.putMember("relatedIds", relatedIds);
            }
            Object noViolations = helperBindings.get("noViolations");
            if (noViolations != null) {
                jsBindings.putMember("noViolations", noViolations);
            }
        } catch (Throwable ignored) {
        }

        try {
            jsBindings.putMember("identityInfo", ProxyObject.fromMap(identityInfo));
            jsBindings.putMember("rctx", ProxyObject.fromMap(resourceBindings));
            jsBindings.putMember("pctx", ProxyObject.fromMap(principalBindings));
        } catch (Throwable ignored) {
        }
    }
}
