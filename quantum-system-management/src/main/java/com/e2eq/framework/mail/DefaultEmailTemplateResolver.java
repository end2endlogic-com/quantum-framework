package com.e2eq.framework.mail;

import com.e2eq.framework.model.persistent.email.EmailTemplate;
import com.e2eq.framework.model.persistent.morphia.EmailTemplateRepo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@ApplicationScoped
public class DefaultEmailTemplateResolver implements EmailTemplateResolver {

    private final EmailTemplateRepo emailTemplateRepo;

    @Inject
    public DefaultEmailTemplateResolver(EmailTemplateRepo emailTemplateRepo) {
        this.emailTemplateRepo = emailTemplateRepo;
    }

    @Override
    public Optional<EmailTemplateDefinition> resolve(String realm, String templateKey) {
        String normalizedKey = normalize(templateKey);
        if (normalizedKey == null) {
            return Optional.empty();
        }

        if (realm != null && !realm.isBlank()) {
            Optional<EmailTemplate> override = emailTemplateRepo.findActiveByTemplateKey(realm, normalizedKey);
            if (override.isPresent()) {
                EmailTemplate template = override.get();
                return Optional.of(new EmailTemplateDefinition(
                    normalizedKey,
                    template.getSubjectTemplate(),
                    template.getHtmlTemplate(),
                    template.getTextTemplate(),
                    EmailTemplateDefinition.Origin.REALM_DB
                ));
            }
        }

        String subjectTemplate = readClasspathTemplate(normalizedKey, "subject.txt");
        String htmlTemplate = readClasspathTemplate(normalizedKey, "body.html");
        String textTemplate = readClasspathTemplate(normalizedKey, "body.txt");
        if (subjectTemplate == null && htmlTemplate == null && textTemplate == null) {
            return Optional.empty();
        }

        return Optional.of(new EmailTemplateDefinition(
            normalizedKey,
            subjectTemplate,
            htmlTemplate,
            textTemplate,
            EmailTemplateDefinition.Origin.CLASSPATH
        ));
    }

    private String readClasspathTemplate(String templateKey, String fileName) {
        String path = "templates/email/" + templateKey + "/" + fileName;
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = getClass().getClassLoader();
        }
        try (InputStream inputStream = classLoader.getResourceAsStream(path)) {
            if (inputStream == null) {
                return null;
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read email template resource: " + path, e);
        }
    }

    private String normalize(String templateKey) {
        if (templateKey == null) {
            return null;
        }
        String trimmed = templateKey.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
