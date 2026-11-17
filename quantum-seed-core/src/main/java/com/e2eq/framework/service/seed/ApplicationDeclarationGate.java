package com.e2eq.framework.service.seed;

import com.e2eq.framework.model.security.Application;
import com.e2eq.framework.model.security.ApplicationDeclarationValidator;
import com.e2eq.framework.model.security.ApplicationDeclarationValidator.Code;
import com.e2eq.framework.model.security.ApplicationDeclarationValidator.Declaration;
import com.e2eq.framework.model.security.ApplicationDeclarationValidator.Diagnostic;
import com.e2eq.framework.model.security.ApplicationDeclarationValidator.Severity;
import com.e2eq.framework.model.security.ApplicationDeclarationValidator.VocabularyEnforcement;
import com.e2eq.framework.model.security.FunctionalArea;
import com.e2eq.framework.model.security.FunctionalDomain;
import com.e2eq.framework.model.security.Policy;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Checks an application identity pack's declaration with {@link ApplicationDeclarationValidator}
 * before any of it is written: its application row (roles, owner/member roles, tenancy, uses), its
 * functional areas and domains, and its policies, against the vocabulary other applications already
 * hold in the realm being seeded. A policy rule naming a domain or action the application neither
 * declares nor borrows is a {@code POLICY_TRIPLE_UNDECLARED} diagnostic.
 *
 * <p>A pack is an identity pack when it has an {@code application} dataset; other packs pass
 * untouched.
 *
 * <p>Outcome, by {@link VocabularyEnforcement} ({@code quantum.identity-contribution.vocabulary-enforcement}):
 * <ul>
 *   <li>Declaration errors (reserved or undeclared roles, invalid tenancy, malformed rows, rows
 *       tagged for another application) refuse in every mode, as identity-contribution admission
 *       does.</li>
 *   <li>Vocabulary diagnostics are warnings under WARN (logged with their code; the pack loads) and
 *       refuse under REFUSE.</li>
 * </ul>
 * A refusal throws {@link ApplicationDeclarationRefusedException} naming the pack and each
 * offending reference; {@link SeedLoader} has written nothing at that point.
 */
public final class ApplicationDeclarationGate implements SeedPackGate {

    static final String COLLECTION_APPLICATION = "application";
    static final String COLLECTION_FUNCTIONAL_AREA = "functionalArea";
    static final String COLLECTION_FUNCTIONAL_DOMAIN = "functionalDomain";
    static final String COLLECTION_POLICY = "policy";

    /** The vocabulary the realm already holds, all applications included. */
    public interface HeldVocabulary {
        List<FunctionalArea> areas(String realm);

        List<FunctionalDomain> domains(String realm);
    }

    private final VocabularyEnforcement enforcement;
    private final HeldVocabulary held;
    private final ObjectMapper mapper;

    public ApplicationDeclarationGate(VocabularyEnforcement enforcement, HeldVocabulary held, ObjectMapper objectMapper) {
        this.enforcement = enforcement == null ? VocabularyEnforcement.WARN : enforcement;
        this.held = Objects.requireNonNull(held, "held");
        this.mapper = Objects.requireNonNull(objectMapper, "objectMapper").copy()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public VocabularyEnforcement enforcement() {
        return enforcement;
    }

    @Override
    public void admit(SeedPackDescriptor descriptor, SeedContext context, DatasetRecords records) {
        Map<String, List<Map<String, Object>>> rows = vocabularyRows(descriptor, records);
        List<Map<String, Object>> applications = rows.get(COLLECTION_APPLICATION);
        if (applications == null) {
            return; // not an application identity pack
        }
        String pack = descriptor.identity();
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (applications.size() != 1) {
            Severity severity = enforcement == VocabularyEnforcement.REFUSE ? Severity.ERROR : Severity.WARNING;
            diagnostics.add(new Diagnostic(Code.APPLICATION_DECLARATION_AMBIGUOUS, severity, "Pack " + pack + " carries "
                    + applications.size() + " application rows; an identity pack declares exactly one application."));
            report(pack, null, diagnostics);
            return;
        }
        Application application;
        try {
            application = mapper.convertValue(vocabularyOnly(applications.get(0)), Application.class);
        } catch (IllegalArgumentException e) {
            diagnostics.add(new Diagnostic(Code.VOCABULARY_ROW_INVALID, Severity.ERROR,
                    "The application row is not a valid Application: " + rootMessage(e)));
            report(pack, null, diagnostics);
            return;
        }
        String applicationId = application.getRefName();
        Declaration declaration = new Declaration(application,
                convert(rows.get(COLLECTION_FUNCTIONAL_AREA), FunctionalArea.class, diagnostics),
                convert(rows.get(COLLECTION_FUNCTIONAL_DOMAIN), FunctionalDomain.class, diagnostics),
                convert(rows.get(COLLECTION_POLICY), Policy.class, diagnostics));
        String realm = context.getRealm();
        diagnostics.addAll(ApplicationDeclarationValidator.validate(declaration,
                ApplicationDeclarationValidator.heldVocabulary(applicationId, held.areas(realm), held.domains(realm)),
                enforcement));
        report(pack, applicationId, diagnostics);
    }

    private void report(String pack, String applicationId, List<Diagnostic> diagnostics) {
        List<Diagnostic> errors = new ArrayList<>();
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.severity() == Severity.ERROR) {
                errors.add(diagnostic);
            } else {
                Log.warnf("identity-pack.declaration.warning pack=%s app=%s code=%s %s",
                        pack, applicationId, diagnostic.code(), diagnostic.message());
            }
        }
        if (!errors.isEmpty()) {
            throw new ApplicationDeclarationRefusedException(pack, applicationId, errors);
        }
    }

    private Map<String, List<Map<String, Object>>> vocabularyRows(SeedPackDescriptor descriptor, DatasetRecords records) {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (SeedPackManifest.Dataset dataset : descriptor.getManifest().getDatasets()) {
            SeedCollectionResolver.ensureCollectionSet(dataset);
            String collection = dataset.getCollection();
            if (COLLECTION_APPLICATION.equals(collection) || COLLECTION_FUNCTIONAL_AREA.equals(collection)
                    || COLLECTION_FUNCTIONAL_DOMAIN.equals(collection) || COLLECTION_POLICY.equals(collection)) {
                rows.computeIfAbsent(collection, k -> new ArrayList<>()).addAll(records.read(dataset));
            }
        }
        return rows;
    }

    private <T> List<T> convert(List<Map<String, Object>> rows, Class<T> type, List<Diagnostic> diagnostics) {
        List<T> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (int i = 0; i < rows.size(); i++) {
            try {
                out.add(mapper.convertValue(vocabularyOnly(rows.get(i)), type));
            } catch (IllegalArgumentException e) {
                diagnostics.add(new Diagnostic(Code.VOCABULARY_ROW_INVALID, Severity.ERROR, type.getSimpleName()
                        + " row " + (i + 1) + " is not valid: " + rootMessage(e)));
            }
        }
        return out;
    }

    /** The row without its ownership block: the declaration check does not read it. */
    private static Map<String, Object> vocabularyOnly(Map<String, Object> row) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        copy.remove("dataDomain");
        return copy;
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null ? root.getClass().getSimpleName() : message.lines().findFirst().orElse(message);
    }
}
