package com.e2eq.framework.model.persistent.migration.base;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Selection and validation only. MigrationService retains locking and transactional execution. */
final class MigrationPlanSupport {
    private MigrationPlanSupport() { }

    static MigrationPlan plan(String realm, String target, String scope, List<ChangeSetBean> catalog) {
        if (org.semver4j.Semver.parse(target) == null)
            throw failure("MIGRATION_TARGET_VERSION_INVALID", target);
        List<MigrationPlan.Entry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ChangeSetBean bean : catalog) {
            if (!seen.add(bean.getName())) throw failure("MIGRATION_CATALOG_AMBIGUOUS", bean.getName());
            if (!applicable(bean, realm)) continue;
            requireLocalTarget(bean, realm);
            if (org.semver4j.Semver.parse(bean.getDbToVersion()) == null
                    || new org.semver4j.Semver(bean.getDbToVersion()).isGreaterThan(target))
                throw failure("MIGRATION_CATALOG_TARGET_CONFLICT", bean.getName());
            entries.add(pin(bean));
        }
        return new MigrationPlan(1, realm, target, scope, entries);
    }

    static List<ChangeSetBean> validate(MigrationPlan plan, String target, String scope, List<ChangeSetBean> catalog) {
        if (!Objects.equals(plan.targetVersion(), target) || !Objects.equals(plan.databaseScope(), scope))
            throw failure("MIGRATION_PLAN_RUNTIME_DRIFT", plan.realm());
        Map<String, ChangeSetBean> byName = new HashMap<>();
        for (ChangeSetBean bean : catalog)
            if (byName.putIfAbsent(bean.getName(), bean) != null)
                throw failure("MIGRATION_CATALOG_AMBIGUOUS", bean.getName());
        Set<String> seen = new HashSet<>();
        List<ChangeSetBean> selected = new ArrayList<>();
        for (MigrationPlan.Entry entry : plan.entries()) {
            ChangeSetBean bean = byName.get(entry.name());
            if (!seen.add(entry.name()) || bean == null || !applicable(bean, plan.realm()))
                throw failure("MIGRATION_PLAN_ENTRY_UNAVAILABLE", entry.name());
            requireLocalTarget(bean, plan.realm());
            if (!entry.equals(pin(bean))) throw failure("MIGRATION_PLAN_DEFINITION_DRIFT", entry.name());
            selected.add(bean);
        }
        return selected;
    }

    static boolean alreadyApplied(MigrationPlan.Entry entry, Optional<ChangeSetRecord> previous) {
        if (previous.isEmpty()) return false;
        ChangeSetRecord record = previous.get();
        if (!record.isSuccessful()) throw failure("MIGRATION_RECEIPT_UNSUCCESSFUL", entry.name());
        if (record.getChangeSetVersion() > entry.version())
            throw failure("MIGRATION_RECEIPT_NEWER_THAN_PLAN", entry.name());
        if (record.getChangeSetVersion() < entry.version()) return false;
        if (record.getDefinitionSha256() == null)
            throw failure("MIGRATION_RECEIPT_UNPINNED", entry.name());
        if (!entry.definitionSha256().equals(record.getDefinitionSha256()))
            throw failure("MIGRATION_RECEIPT_CONFLICT", entry.name());
        return true;
    }

    static MigrationPlan.Entry pin(ChangeSetBean bean) {
        var fields = new TreeMap<String, Object>();
        fields.put("dataPlane",bean.getDataPlane().name());
        fields.put("id", bean.getId()); fields.put("name", bean.getName());
        fields.put("version", bean.getChangeSetVersion()); fields.put("checksum", bean.getChecksum());
        fields.put("from", bean.getDbFromVersion()); fields.put("fromInt", bean.getDbFromVersionInt());
        fields.put("to", bean.getDbToVersion()); fields.put("toInt", bean.getDbToVersionInt());
        fields.put("priority", bean.getPriority()); fields.put("scope", bean.getScope());
        fields.put("author", bean.getAuthor()); fields.put("description", bean.getDescription());
        fields.put("override", bean.isOverrideDatabase()); fields.put("overrideRealm", bean.getOverrideDatabaseName());
        fields.put("applicableRealms", bean.getApplicableDatabases() == null ? null : new TreeSet<>(bean.getApplicableDatabases()));
        // CDI-generated subclasses are deployment machinery, not the migration implementation.
        Object instance = io.quarkus.arc.ClientProxy.unwrap(bean);
        Class<?> type = instance.getClass();
        while (type.getName().endsWith("_Subclass") || type.getName().endsWith("_ClientProxy")) type = type.getSuperclass();
        fields.put("implementation", type.getName());
        try (InputStream in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            if (in == null) throw failure("MIGRATION_IMPLEMENTATION_UNAVAILABLE", bean.getName());
            fields.put("implementationSha256", hash(in.readAllBytes()));
            return new MigrationPlan.Entry(bean.getName(), bean.getChangeSetVersion(),
                    hash(new ObjectMapper().writeValueAsBytes(fields)));
        } catch (IOException e) {
            throw new IllegalStateException("MIGRATION_PLAN_HASH_FAILED: " + bean.getName(), e);
        }
    }

    private static boolean applicable(ChangeSetBean bean, String realm) {
        Set<String> realms = bean.getApplicableDatabases();
        return realms == null || realms.isEmpty() || realms.contains(realm);
    }

    private static void requireLocalTarget(ChangeSetBean bean, String realm) {
        if (bean.isOverrideDatabase() && !Objects.equals(bean.getOverrideDatabaseName(), realm))
            throw failure("MIGRATION_PLAN_CROSS_REALM_UNSUPPORTED", bean.getName());
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("MIGRATION_PLAN_HASH_FAILED", e); }
    }

    private static IllegalStateException failure(String code, String value) {
        return new IllegalStateException(code + ": " + value);
    }
}
