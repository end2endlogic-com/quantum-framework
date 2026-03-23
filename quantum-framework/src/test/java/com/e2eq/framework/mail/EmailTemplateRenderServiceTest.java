package com.e2eq.framework.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailTemplateRenderServiceTest {

    @Test
    void classpathTemplateRendersSubjectHtmlAndText() {
        DefaultEmailTemplateResolver resolver = new DefaultEmailTemplateResolver(null);
        EmailTemplateRenderService renderService = new EmailTemplateRenderService(
            Engine.builder().addDefaults().build(),
            new ObjectMapper(),
            resolver
        );

        RenderedTemplate rendered = renderService.render(null, "framework-test", Map.of(
            "name", "Quantum",
            "realm", "demo-psa-com"
        ));

        assertEquals("Welcome Quantum", rendered.subject().trim());
        assertTrue(rendered.htmlBody().contains("demo-psa-com"));
        assertTrue(rendered.textBody().contains("Quantum"));
    }

    @Test
    void resolverReturnsClasspathTemplateWhenPresent() {
        DefaultEmailTemplateResolver resolver = new DefaultEmailTemplateResolver(null);

        Optional<EmailTemplateDefinition> definition = resolver.resolve(null, "framework-test");

        assertTrue(definition.isPresent());
        assertTrue(definition.get().subjectTemplate().contains("{name}"));
    }
}
