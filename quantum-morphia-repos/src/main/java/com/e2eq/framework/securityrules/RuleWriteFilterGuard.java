package com.e2eq.framework.security.runtime;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.MorphiaUtils;
import com.e2eq.framework.model.securityrules.FilterJoinOp;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityCheckException;
import com.e2eq.framework.model.securityrules.SecurityCheckResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.quarkus.logging.Log;
import org.bson.types.ObjectId;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Write-side counterpart of {@link RuleContext#getFilters}: the entity being created or updated
 * (in particular its {@link DataDomain}) must satisfy every matched ALLOW rule filter for the
 * write action, evaluated in memory against the entity with the same variable bundle that reads
 * use. Otherwise a caller could write a record into a data domain it could never read or write,
 * e.g. by supplying {@code dataDomain} in a request body.
 *
 * <p>Fails closed: an unresolved filter variable, a filter that cannot be compiled or evaluated,
 * or an entity that cannot be converted for evaluation all deny the write.</p>
 */
final class RuleWriteFilterGuard {

    private static final ObjectMapper MAPPER = buildMapper();

    private final RuleVariableBundleResolver variableBundleResolver;

    RuleWriteFilterGuard(RuleVariableBundleResolver variableBundleResolver) {
        this.variableBundleResolver = variableBundleResolver;
    }

    void enforce(PrincipalContext pcontext,
                 ResourceContext rcontext,
                 SecurityCheckResponse response,
                 List<SecurityCheckResponse.RuleFilterInfo> filterInfos,
                 UnversionedBaseModel entity) {
        if (filterInfos == null || filterInfos.isEmpty()) {
            // No matched ALLOW rule constrains this action with a filter.
            return;
        }
        if (entity.getDataDomain() == null) {
            // Nothing places this record in a data domain: ValidationInterceptor assigns one on
            // every validated write, so a null domain only reaches here for partial writes that
            // skip validation (e.g. merge of changed fields), which do not set or move the
            // record's stored domain and cannot be evaluated as a whole record.
            if (Log.isDebugEnabled()) {
                Log.debugf("Write filter check skipped for %s: no dataDomain on the persisted instance",
                        entity.getClass().getSimpleName());
            }
            return;
        }

        MorphiaUtils.VariableBundle vars;
        try {
            vars = variableBundleResolver.resolveVariableBundle(pcontext, rcontext, entity.getClass());
        } catch (RuntimeException e) {
            throw deny(response, rcontext, entity, null, "rule filter variables could not be resolved: " + e.getMessage());
        }

        JsonNode facts;
        try {
            facts = MAPPER.valueToTree(entity);
        } catch (RuntimeException e) {
            throw deny(response, rcontext, entity, null, "entity could not be converted for rule filter evaluation: " + e.getMessage());
        }

        for (SecurityCheckResponse.RuleFilterInfo info : filterInfos) {
            if (info == null) {
                continue;
            }
            String and = blankToNull(info.getAndFilterString());
            String or = blankToNull(info.getOrFilterString());
            if (and == null && or == null) {
                continue;
            }

            Set<String> unresolved = RuleFilterVariables.unresolved(vars, and, or);
            if (!unresolved.isEmpty()) {
                Log.warnf("Write denied: rule '%s' filter references unresolved variable(s) %s (resolver failed: %s); failing closed",
                        info.getRuleName(), unresolved, RuleFilterVariables.failed(vars, unresolved));
                throw deny(response, rcontext, entity, info.getRuleName(),
                        "rule filter references unresolved variable(s) " + unresolved);
            }

            boolean ok;
            try {
                Boolean andOk = (and == null) ? null : compile(and, vars).test(facts);
                Boolean orOk = (or == null) ? null : compile(or, vars).test(facts);
                if (andOk != null && orOk != null) {
                    FilterJoinOp joinOp = (info.getJoinOp() != null && !info.getJoinOp().isBlank())
                            ? FilterJoinOp.valueOf(info.getJoinOp()) : FilterJoinOp.AND;
                    ok = joinOp == FilterJoinOp.OR ? (andOk || orOk) : (andOk && orOk);
                } else {
                    ok = (andOk != null) ? andOk : orOk;
                }
            } catch (Exception e) {
                throw deny(response, rcontext, entity, info.getRuleName(),
                        "rule filter could not be evaluated against the entity: " + e.getMessage());
            }

            if (!ok) {
                throw deny(response, rcontext, entity, info.getRuleName(),
                        "the record does not satisfy the rule filter");
            }
        }
    }

    private Predicate<JsonNode> compile(String query, MorphiaUtils.VariableBundle vars) {
        return com.e2eq.framework.query.runtime.QueryPredicates.compilePredicate(query, vars.strings, vars.objects);
    }

    private static SecurityCheckException deny(SecurityCheckResponse response,
                                                ResourceContext rcontext,
                                                UnversionedBaseModel entity,
                                                String ruleName,
                                                String reason) {
        DataDomain dd = entity.getDataDomain();
        String message = String.format(
                "Write denied: user=%s may not %s %s in area=%s, domain=%s for dataDomain=%s%s: %s",
                response != null && response.getPrincipalContext() != null ? response.getPrincipalContext().getUserId() : "?",
                rcontext.getAction(),
                entity.getClass().getSimpleName(),
                rcontext.getArea(),
                rcontext.getFunctionalDomain(),
                describe(dd),
                ruleName != null ? " (rule '" + ruleName + "')" : "",
                reason);
        Log.warn(message);
        if (response != null) {
            response.setFinalEffect(RuleEffect.DENY);
        }
        return new SecurityCheckException(response, message);
    }

    private static String describe(DataDomain dd) {
        if (dd == null) {
            return "null";
        }
        return "{orgRefName=" + dd.getOrgRefName()
                + ", accountNum=" + dd.getAccountNum()
                + ", tenantId=" + dd.getTenantId()
                + ", dataSegment=" + dd.getDataSegment()
                + ", ownerId=" + dd.getOwnerId() + "}";
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static ObjectMapper buildMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        SimpleModule ids = new SimpleModule("rule-write-filter-ids");
        ids.addSerializer(ObjectId.class, ToStringSerializer.instance);
        mapper.registerModule(ids);
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
