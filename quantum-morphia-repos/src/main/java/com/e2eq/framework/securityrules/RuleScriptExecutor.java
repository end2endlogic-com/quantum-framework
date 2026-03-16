package com.e2eq.framework.security.runtime;

import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import io.quarkus.logging.Log;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
                installHelpersAndBindings(context, pcontext, rcontext, true);
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
                        .allowHostAccess(HostAccess.NONE)
                        .allowHostClassLookup(className -> false)
                        .allowIO(IOAccess.NONE)
                        .option("js.ecmascript-version", "2021")
                        .build()) {
                    installHelpersAndBindings(context, pcontext, rcontext, false);
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

    private void installHelpersAndBindings(Context context, PrincipalContext pcontext, ResourceContext rcontext, boolean bindRawContexts) {
        var jsBindings = context.getBindings("js");

        Map<String, Object> helperBindings = new HashMap<>();
        Map<String, Object> resourceBindings = buildResourceBindings(rcontext);
        Map<String, Object> principalBindings = buildPrincipalBindings(pcontext);
        Map<String, Object> identityInfo = new HashMap<>();
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
            bindHelper(jsBindings, "isA", helperBindings.get("isA"));
            bindHelper(jsBindings, "hasLabel", helperBindings.get("hasLabel"));
            bindHelper(jsBindings, "hasEdge", helperBindings.get("hasEdge"));
            bindHelper(jsBindings, "hasAnyEdge", helperBindings.get("hasAnyEdge"));
            bindHelper(jsBindings, "hasAllEdges", helperBindings.get("hasAllEdges"));
            bindHelper(jsBindings, "relatedIds", helperBindings.get("relatedIds"));
            bindHelper(jsBindings, "noViolations", helperBindings.get("noViolations"));
        } catch (Throwable ignored) {
        }

        try {
            ProxyObject principalProxy = ProxyObject.fromMap(principalBindings);
            ProxyObject resourceProxy = ProxyObject.fromMap(resourceBindings);
            jsBindings.putMember("identityInfo", ProxyObject.fromMap(identityInfo));
            jsBindings.putMember("rctx", resourceProxy);
            jsBindings.putMember("pctx", principalProxy);
            jsBindings.putMember("rcontext", bindRawContexts && rcontext != null ? rcontext : resourceProxy);
            jsBindings.putMember("pcontext", bindRawContexts && pcontext != null ? pcontext : principalProxy);
        } catch (Throwable ignored) {
        }
    }

    private Map<String, Object> buildResourceBindings(ResourceContext rcontext) {
        Map<String, Object> resourceBindings = new HashMap<>();
        resourceBindings.put("area", rcontext != null ? rcontext.getArea() : null);
        resourceBindings.put("functionalDomain", rcontext != null ? rcontext.getFunctionalDomain() : null);
        resourceBindings.put("action", rcontext != null ? rcontext.getAction() : null);
        resourceBindings.put("resourceId", rcontext != null ? rcontext.getResourceId() : null);
        resourceBindings.put("ownerId", rcontext != null ? rcontext.getOwnerId() : null);
        resourceBindings.put("realm", rcontext != null ? rcontext.getRealm() : null);
        resourceBindings.put("getArea", (ProxyExecutable) args -> rcontext != null ? rcontext.getArea() : null);
        resourceBindings.put("getFunctionalDomain", (ProxyExecutable) args -> rcontext != null ? rcontext.getFunctionalDomain() : null);
        resourceBindings.put("getAction", (ProxyExecutable) args -> rcontext != null ? rcontext.getAction() : null);
        resourceBindings.put("getResourceId", (ProxyExecutable) args -> rcontext != null ? rcontext.getResourceId() : null);
        resourceBindings.put("getOwnerId", (ProxyExecutable) args -> rcontext != null ? rcontext.getOwnerId() : null);
        resourceBindings.put("getRealm", (ProxyExecutable) args -> rcontext != null ? rcontext.getRealm() : null);
        try {
            Set<String> resourceLabels = labelService != null ? labelService.labelsFor(rcontext) : Set.of();
            resourceBindings.put("labels", new ArrayList<>(resourceLabels));
            resourceBindings.put("getLabels", (ProxyExecutable) args -> new ArrayList<>(resourceLabels));
        } catch (Throwable ignored) {
        }
        return resourceBindings;
    }

    private Map<String, Object> buildPrincipalBindings(PrincipalContext pcontext) {
        Map<String, Object> principalBindings = new HashMap<>();
        principalBindings.put("userId", pcontext != null ? pcontext.getUserId() : null);
        principalBindings.put("defaultRealm", pcontext != null ? pcontext.getDefaultRealm() : null);
        principalBindings.put("scope", pcontext != null ? pcontext.getScope() : null);
        principalBindings.put("roles", extractRoles(pcontext));
        principalBindings.put("getUserId", (ProxyExecutable) args -> pcontext != null ? pcontext.getUserId() : null);
        principalBindings.put("getDefaultRealm", (ProxyExecutable) args -> pcontext != null ? pcontext.getDefaultRealm() : null);
        principalBindings.put("getScope", (ProxyExecutable) args -> pcontext != null ? pcontext.getScope() : null);
        principalBindings.put("getRoles", (ProxyExecutable) args -> extractRoles(pcontext));
        Map<String, Object> dataDomainBindings = new HashMap<>();
        try {
            if (pcontext != null && pcontext.getDataDomain() != null) {
                dataDomainBindings.put("orgRefName", pcontext.getDataDomain().getOrgRefName());
                dataDomainBindings.put("accountNum", pcontext.getDataDomain().getAccountNum());
                dataDomainBindings.put("tenantId", pcontext.getDataDomain().getTenantId());
                dataDomainBindings.put("dataSegment", pcontext.getDataDomain().getDataSegment());
                dataDomainBindings.put("ownerId", pcontext.getDataDomain().getOwnerId());
                dataDomainBindings.put("getOrgRefName", (ProxyExecutable) args -> pcontext.getDataDomain().getOrgRefName());
                dataDomainBindings.put("getAccountNum", (ProxyExecutable) args -> pcontext.getDataDomain().getAccountNum());
                dataDomainBindings.put("getTenantId", (ProxyExecutable) args -> pcontext.getDataDomain().getTenantId());
                dataDomainBindings.put("getDataSegment", (ProxyExecutable) args -> pcontext.getDataDomain().getDataSegment());
                dataDomainBindings.put("getOwnerId", (ProxyExecutable) args -> pcontext.getDataDomain().getOwnerId());
            }
        } catch (Throwable ignored) {
        }
        principalBindings.put("dataDomain", dataDomainBindings);
        principalBindings.put("getDataDomain", (ProxyExecutable) args -> ProxyObject.fromMap(dataDomainBindings));
        try {
            Set<String> principalLabels = labelService != null ? labelService.labelsFor(pcontext) : Set.of();
            principalBindings.put("labels", new ArrayList<>(principalLabels));
            principalBindings.put("getLabels", (ProxyExecutable) args -> new ArrayList<>(principalLabels));
        } catch (Throwable ignored) {
        }
        return principalBindings;
    }

    private List<String> extractRoles(PrincipalContext pcontext) {
        List<String> roles = new ArrayList<>();
        try {
            if (pcontext == null) {
                return roles;
            }
            Object rolesObj = pcontext.getRoles();
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
        } catch (Throwable ignored) {
        }
        return roles;
    }

    private void bindHelper(Value jsBindings, String name, Object helper) {
        if (helper == null) {
            return;
        }
        Method applyMethod = findApplyMethod(helper);
        if (applyMethod == null) {
            return;
        }
        jsBindings.putMember(name, (ProxyExecutable) args -> {
            Object[] invocationArgs = new Object[applyMethod.getParameterCount()];
            for (int i = 0; i < invocationArgs.length; i++) {
                invocationArgs[i] = i < args.length ? args[i].as(Object.class) : null;
            }
            try {
                return applyMethod.invoke(helper, invocationArgs);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Failed to invoke script helper " + name, e);
            }
        });
    }

    private Method findApplyMethod(Object helper) {
        for (Method method : helper.getClass().getMethods()) {
            if ("apply".equals(method.getName()) && method.getParameterCount() <= 2) {
                return method;
            }
        }
        return null;
    }
}
