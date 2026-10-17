package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.email.EmailTemplate;
import dev.morphia.Datastore;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

@ApplicationScoped
public class EmailTemplateRepo extends MorphiaRepo<EmailTemplate> {

    public Optional<EmailTemplate> findByTemplateKey(String realm, String templateKey) {
        if (realm == null || realm.isBlank() || templateKey == null || templateKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(morphiaDataStoreWrapper.getDataStore(realm)
            .find(EmailTemplate.class)
            .filter(Filters.eq("templateKey", templateKey.trim()))
            .first());
    }

    public Optional<EmailTemplate> findActiveByTemplateKey(String realm, String templateKey) {
        if (realm == null || realm.isBlank() || templateKey == null || templateKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(morphiaDataStoreWrapper.getDataStore(realm)
            .find(EmailTemplate.class)
            .filter(
                Filters.eq("templateKey", templateKey.trim()),
                Filters.eq("active", true)
            )
            .first());
    }

    /**
     * Deletes by refName through the same governed read as GET/PUT ({@link #findByRefName(String, String)}
     * under the caller's rule filters), then the gated by-id delete; a template outside the
     * caller's scope is not found.
     */
    public boolean deleteByRefName(String realm, String refName) {
        if (realm == null || realm.isBlank() || refName == null || refName.isBlank()) {
            return false;
        }
        Optional<EmailTemplate> template = findByRefName(refName, realm);
        if (template.isEmpty()) {
            return false;
        }
        try {
            return delete(realm, template.get()) > 0;
        } catch (com.e2eq.framework.exceptions.ReferentialIntegrityViolationException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
}
