package com.e2eq.framework.mail;

import com.e2eq.framework.model.messaging.MessageChannel;
import com.e2eq.framework.model.persistent.email.EmailTemplate;
import com.e2eq.framework.model.persistent.email.MessageTemplateVariant;
import com.e2eq.framework.model.persistent.morphia.EmailTemplateRepo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolves templates from the realm's {@code email_templates} override, then the classpath.
 *
 * <p>By channel and locale: the requested locale is tried first in the realm override and
 * then on the classpath, then the default locale the same way. Classpath layout:
 * {@code templates/email/{key}/} (default locale, unchanged) or
 * {@code templates/{channel}/{key}/{locale}/}, each with {@code subject.txt},
 * {@code body.html} and/or {@code body.txt}.
 */
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

    @Override
    public Optional<EmailTemplateDefinition> resolve(String realm, String templateKey, MessageChannel channel, String locale) {
        String normalizedKey = normalize(templateKey);
        if (normalizedKey == null || channel == null) {
            return Optional.empty();
        }
        String requestedLocale = normalizeLocale(locale);
        List<String> locales = new ArrayList<>();
        if (requestedLocale != null) {
            locales.add(requestedLocale);
        }
        locales.add(null);

        Optional<EmailTemplate> override = realm == null || realm.isBlank()
            ? Optional.empty()
            : emailTemplateRepo.findActiveByTemplateKey(realm, normalizedKey);

        for (String candidate : locales) {
            Optional<EmailTemplateDefinition> fromRealm = override
                .flatMap(template -> fromRealmTemplate(normalizedKey, template, channel, candidate));
            if (fromRealm.isPresent()) {
                return fromRealm;
            }
            Optional<EmailTemplateDefinition> fromClasspath = fromClasspath(normalizedKey, channel, candidate);
            if (fromClasspath.isPresent()) {
                return fromClasspath;
            }
        }
        return Optional.empty();
    }

    private Optional<EmailTemplateDefinition> fromRealmTemplate(
        String templateKey, EmailTemplate template, MessageChannel channel, String locale
    ) {
        List<MessageTemplateVariant> matches = new ArrayList<>();
        if (template.getVariants() != null) {
            for (MessageTemplateVariant variant : template.getVariants()) {
                if (variant != null && variant.isActive() && variant.getChannel() == channel
                    && sameLocale(normalizeLocale(variant.getLocale()), locale)) {
                    matches.add(variant);
                }
            }
        }
        if (matches.size() > 1) {
            throw new IllegalStateException("Template " + templateKey + " has " + matches.size()
                + " active variants for channel " + channel + " and locale " + (locale == null ? "default" : locale));
        }
        if (matches.size() == 1) {
            MessageTemplateVariant variant = matches.get(0);
            return Optional.of(new EmailTemplateDefinition(
                templateKey,
                variant.getSubjectTemplate(),
                variant.getHtmlTemplate(),
                variant.getTextTemplate(),
                EmailTemplateDefinition.Origin.REALM_DB,
                channel,
                locale,
                normalize(variant.getProviderTemplateId()),
                normalize(variant.getSenderSignature()),
                variant.getParameterNames()
            ));
        }
        // The document's own fields are the email template in the default locale.
        if (channel == MessageChannel.EMAIL && locale == null && hasContent(
            template.getSubjectTemplate(), template.getHtmlTemplate(), template.getTextTemplate())) {
            return Optional.of(new EmailTemplateDefinition(
                templateKey,
                template.getSubjectTemplate(),
                template.getHtmlTemplate(),
                template.getTextTemplate(),
                EmailTemplateDefinition.Origin.REALM_DB,
                MessageChannel.EMAIL,
                null,
                null,
                null,
                template.getParameterNames()
            ));
        }
        return Optional.empty();
    }

    private Optional<EmailTemplateDefinition> fromClasspath(String templateKey, MessageChannel channel, String locale) {
        String directory = "templates/" + channel.pathSegment() + "/" + templateKey + (locale == null ? "" : "/" + locale);
        String subjectTemplate = readClasspathResource(directory + "/subject.txt");
        String htmlTemplate = readClasspathResource(directory + "/body.html");
        String textTemplate = readClasspathResource(directory + "/body.txt");
        if (subjectTemplate == null && htmlTemplate == null && textTemplate == null) {
            return Optional.empty();
        }
        return Optional.of(new EmailTemplateDefinition(
            templateKey,
            subjectTemplate,
            htmlTemplate,
            textTemplate,
            EmailTemplateDefinition.Origin.CLASSPATH,
            channel,
            locale,
            null,
            null,
            List.of()
        ));
    }

    /** Canonical BCP 47 tag, or {@code null} for blank or undetermined input; never contains a path separator. */
    static String normalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return null;
        }
        String tag = Locale.forLanguageTag(locale.trim().replace('_', '-')).toLanguageTag();
        return "und".equals(tag) ? null : tag;
    }

    private static boolean sameLocale(String left, String right) {
        return left == null ? right == null : left.equalsIgnoreCase(right);
    }

    private static boolean hasContent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private String readClasspathTemplate(String templateKey, String fileName) {
        return readClasspathResource("templates/email/" + templateKey + "/" + fileName);
    }

    private String readClasspathResource(String path) {
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
