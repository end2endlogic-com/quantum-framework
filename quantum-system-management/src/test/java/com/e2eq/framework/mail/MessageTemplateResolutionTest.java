package com.e2eq.framework.mail;

import com.e2eq.framework.model.messaging.MessageChannel;
import com.e2eq.framework.model.persistent.email.EmailTemplate;
import com.e2eq.framework.model.persistent.email.MessageTemplateVariant;
import com.e2eq.framework.model.persistent.morphia.EmailTemplateRepo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessageTemplateResolutionTest {

    private static final String REALM = "acme-test";

    private static EmailTemplate realmTemplate(MessageTemplateVariant... variants) {
        EmailTemplate template = new EmailTemplate();
        template.setTemplateKey("auth.magic-link");
        template.setSubjectTemplate("Realm subject");
        template.setTextTemplate("Realm body {link}");
        template.setParameterNames(List.of("link"));
        template.setVariants(List.of(variants));
        return template;
    }

    private static DefaultEmailTemplateResolver resolverWith(EmailTemplate template) {
        EmailTemplateRepo repo = mock(EmailTemplateRepo.class);
        when(repo.findActiveByTemplateKey(REALM, template.getTemplateKey())).thenReturn(Optional.of(template));
        return new DefaultEmailTemplateResolver(repo);
    }

    @Test
    void classpathResolvesRequestedLocaleThenDefault() {
        var resolver = new DefaultEmailTemplateResolver(null);

        var portuguese = resolver.resolve(null, "framework-test", MessageChannel.EMAIL, "pt_br").orElseThrow();
        assertEquals("pt-BR", portuguese.locale());
        assertTrue(portuguese.subjectTemplate().startsWith("Bem-vindo"));
        assertEquals(EmailTemplateDefinition.Origin.CLASSPATH, portuguese.origin());

        var fallback = resolver.resolve(null, "framework-test", MessageChannel.EMAIL, "de-DE").orElseThrow();
        assertNull(fallback.locale(), "an unknown locale falls back to the default locale");
        assertTrue(fallback.subjectTemplate().contains("{name}"));
    }

    @Test
    void classpathResolvesByChannel() {
        var resolver = new DefaultEmailTemplateResolver(null);

        var sms = resolver.resolve(null, "framework-test", MessageChannel.SMS, null).orElseThrow();
        assertEquals(MessageChannel.SMS, sms.channel());
        assertNull(sms.subjectTemplate());
        assertTrue(sms.textTemplate().contains("{link}"));

        assertTrue(resolver.resolve(null, "framework-test", MessageChannel.WHATSAPP, null).isEmpty(),
            "a channel with no template is not answered with another channel's template");
    }

    @Test
    void anSmsTemplateRendersWithoutASubject() {
        var resolver = new DefaultEmailTemplateResolver(null);
        var render = new EmailTemplateRenderService(io.quarkus.qute.Engine.builder().addDefaults().build(),
            new com.fasterxml.jackson.databind.ObjectMapper(), resolver);
        var sms = resolver.resolve(null, "framework-test", MessageChannel.SMS, null).orElseThrow();
        var rendered = render.render(sms, java.util.Map.of("link", "https://x.test/#t"));
        assertNull(rendered.subject());
        assertTrue(rendered.textBody().contains("https://x.test/#t"));
        var email = resolver.resolve(null, "framework-test", MessageChannel.EMAIL, null).orElseThrow();
        var noSubject = new EmailTemplateDefinition(email.templateKey(), " ", null, "plain body", email.origin());
        assertThrows(IllegalStateException.class, () -> render.render(noSubject, java.util.Map.of()),
            "an email still needs a subject");
    }

    @Test
    void localeIsNormalisedSoItCannotEscapeTheTemplateDirectory() {
        assertNull(DefaultEmailTemplateResolver.normalizeLocale("../../etc"));
        assertNull(DefaultEmailTemplateResolver.normalizeLocale("  "));
        assertEquals("pt-BR", DefaultEmailTemplateResolver.normalizeLocale("pt_BR"));
    }

    @Test
    void realmVariantCarriesProviderFieldsAndFallsBackToDefaultLocale() {
        var whatsapp = MessageTemplateVariant.builder()
            .channel(MessageChannel.WHATSAPP)
            .providerTemplateId("HX123")
            .senderSignature("Acme")
            .parameterNames(List.of("link"))
            .build();
        var resolver = resolverWith(realmTemplate(whatsapp));

        var resolved = resolver.resolve(REALM, "auth.magic-link", MessageChannel.WHATSAPP, "fr-FR").orElseThrow();
        assertEquals(EmailTemplateDefinition.Origin.REALM_DB, resolved.origin());
        assertNull(resolved.locale());
        assertTrue(resolved.providerRendered());
        assertEquals("HX123", resolved.providerTemplateId());
        assertEquals("Acme", resolved.senderSignature());
        assertEquals(List.of("link"), resolved.parameterNames());
    }

    @Test
    void realmOverrideBeatsShippedClasspathLocale() {
        EmailTemplate template = realmTemplate();
        template.setTemplateKey("framework-test");
        var resolver = resolverWith(template);

        var resolved = resolver.resolve(REALM, "framework-test", MessageChannel.EMAIL, "pt-BR").orElseThrow();
        assertEquals(EmailTemplateDefinition.Origin.REALM_DB, resolved.origin(),
            "a realm's own default-locale text is not replaced by shipped classpath text");
        assertEquals("Realm subject", resolved.subjectTemplate());
        assertNull(resolved.locale());
        assertEquals(List.of("link"), resolved.parameterNames());

        var noRealm = new DefaultEmailTemplateResolver(null).resolve(null, "framework-test", MessageChannel.EMAIL, "pt-BR").orElseThrow();
        assertEquals("pt-BR", noRealm.locale(), "without a realm override the classpath locale applies");
    }

    @Test
    void realmDocumentWithoutContentForAChannelFallsToClasspath() {
        EmailTemplate smsOnly = realmTemplate(MessageTemplateVariant.builder().channel(MessageChannel.SMS).textTemplate("s").build());
        smsOnly.setTemplateKey("framework-test");
        smsOnly.setSubjectTemplate(null);
        smsOnly.setTextTemplate(null);
        var resolved = resolverWith(smsOnly).resolve(REALM, "framework-test", MessageChannel.EMAIL, null).orElseThrow();
        assertEquals(EmailTemplateDefinition.Origin.CLASSPATH, resolved.origin());
    }

    @Test
    void realmLocaleVariantMatchesCaseInsensitively() {
        var spanish = MessageTemplateVariant.builder()
            .channel(MessageChannel.EMAIL).locale("es-MX")
            .subjectTemplate("Hola").textTemplate("Entrar {link}")
            .build();
        var resolver = resolverWith(realmTemplate(spanish));

        var resolved = resolver.resolve(REALM, "auth.magic-link", MessageChannel.EMAIL, "es-mx").orElseThrow();
        assertEquals("Hola", resolved.subjectTemplate());
        assertEquals("es-MX", resolved.locale());
    }

    @Test
    void inactiveVariantIsSkippedAndDuplicateVariantsAreRefused() {
        var inactive = MessageTemplateVariant.builder().channel(MessageChannel.SMS).active(false).textTemplate("x").build();
        assertTrue(resolverWith(realmTemplate(inactive))
            .resolve(REALM, "auth.magic-link", MessageChannel.SMS, null).isEmpty());

        var first = MessageTemplateVariant.builder().channel(MessageChannel.SMS).textTemplate("a").build();
        var second = MessageTemplateVariant.builder().channel(MessageChannel.SMS).textTemplate("b").build();
        var ambiguous = resolverWith(realmTemplate(first, second));
        assertThrows(IllegalStateException.class,
            () -> ambiguous.resolve(REALM, "auth.magic-link", MessageChannel.SMS, null));
    }

    @Test
    void legacyResolveIgnoresVariantsAndKeepsItsShape() {
        var sms = MessageTemplateVariant.builder().channel(MessageChannel.SMS).textTemplate("sms {link}").build();
        var resolver = resolverWith(realmTemplate(sms));

        var legacy = resolver.resolve(REALM, "auth.magic-link").orElseThrow();
        assertEquals("Realm subject", legacy.subjectTemplate());
        assertEquals(MessageChannel.EMAIL, legacy.channel());
        assertNull(legacy.locale());
        assertEquals(List.of(), legacy.parameterNames(), "the legacy path reports no parameter names, as before");
    }

    @Test
    void legacyDocumentWithoutVariantsResolvesForEmail() {
        EmailTemplate legacy = realmTemplate();
        legacy.setVariants(null);
        legacy.setParameterNames(null);
        var resolved = resolverWith(legacy).resolve(REALM, "auth.magic-link", MessageChannel.EMAIL, "en-US").orElseThrow();
        assertEquals("Realm subject", resolved.subjectTemplate());
        assertEquals(List.of(), resolved.parameterNames());
    }

    @Test
    void preChannelResolverAnswersOnlyEmail() {
        EmailTemplateResolver legacy = (realm, key) -> Optional.of(
            new EmailTemplateDefinition(key, "s", null, "t", EmailTemplateDefinition.Origin.CLASSPATH));
        assertTrue(legacy.resolve(null, "k", MessageChannel.EMAIL, "pt-BR").isPresent());
        assertTrue(legacy.resolve(null, "k", MessageChannel.SMS, null).isEmpty());
    }
}
