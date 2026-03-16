package com.e2eq.framework.securityrules;

import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityCheckResponse;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

final class RuleContextRequestCache {

    private static final ThreadLocal<Map<String, SecurityCheckResponse>> TL_REQUEST_PERMISSION_CACHE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> TL_SKIP_REQUEST_CACHE = new ThreadLocal<>();

    private RuleContextRequestCache() {
    }

    static void initIfAbsent() {
        if (TL_REQUEST_PERMISSION_CACHE.get() == null) {
            TL_REQUEST_PERMISSION_CACHE.set(new HashMap<>());
        }
    }

    static void clear() {
        TL_REQUEST_PERMISSION_CACHE.remove();
    }

    static boolean shouldSkip() {
        Boolean skip = TL_SKIP_REQUEST_CACHE.get();
        return skip != null && skip;
    }

    static void skipForCurrentThread() {
        TL_SKIP_REQUEST_CACHE.set(Boolean.TRUE);
    }

    static void clearSkipForCurrentThread() {
        TL_SKIP_REQUEST_CACHE.remove();
    }

    static SecurityCheckResponse get(String key) {
        Map<String, SecurityCheckResponse> cache = TL_REQUEST_PERMISSION_CACHE.get();
        return cache != null ? cache.get(key) : null;
    }

    static void put(String key, SecurityCheckResponse response) {
        Map<String, SecurityCheckResponse> cache = TL_REQUEST_PERMISSION_CACHE.get();
        if (cache != null) {
            cache.put(key, response);
        }
    }

    static String buildPermissionCacheKey(
            PrincipalContext pctx,
            ResourceContext rctx,
            RuleEffect defaultEffect) {
        String user = String.valueOf(pctx != null ? pctx.getUserId() : "<anon>");
        String realm = String.valueOf(pctx != null ? pctx.getDefaultRealm() : "<realm>");
        String scope = String.valueOf(pctx != null ? pctx.getScope() : "<scope>");
        String roles = Arrays.toString(pctx != null ? pctx.getRoles() : new String[0]);
        String area = String.valueOf(rctx != null ? rctx.getArea() : "<area>");
        String domain = String.valueOf(rctx != null ? rctx.getFunctionalDomain() : "<domain>");
        String action = String.valueOf(rctx != null ? rctx.getAction() : "<action>");
        String dd = String.valueOf(pctx != null && pctx.getDataDomain() != null ? pctx.getDataDomain().toString() : "<dd>");
        return String.join("|", user, realm, scope, roles, area, domain, action, dd, String.valueOf(defaultEffect));
    }
}
