package com.e2eq.framework.mail;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.qute.Engine;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class EmailTemplateRenderService {

    private final Engine engine;
    private final ObjectMapper objectMapper;
    private final EmailTemplateResolver emailTemplateResolver;

    @Inject
    public EmailTemplateRenderService(
        Engine engine,
        ObjectMapper objectMapper,
        EmailTemplateResolver emailTemplateResolver
    ) {
        this.engine = engine;
        this.objectMapper = objectMapper;
        this.emailTemplateResolver = emailTemplateResolver;
    }

    public RenderedTemplate render(String realm, String templateKey, Object context) {
        EmailTemplateDefinition template = emailTemplateResolver.resolve(realm, templateKey)
            .orElseThrow(() -> new IllegalArgumentException("Email template not found: " + templateKey));
        return render(template, context);
    }

    public RenderedTemplate render(EmailTemplateDefinition template, Object context) {
        Map<String, Object> renderData = toRenderData(context);
        String subject = renderTemplate(template.subjectTemplate(), renderData);
        String htmlBody = renderTemplate(template.htmlTemplate(), renderData);
        String textBody = renderTemplate(template.textTemplate(), renderData);

        if (subject == null || subject.isBlank()) {
            throw new IllegalStateException("Rendered email subject is blank for template key " + template.templateKey());
        }
        if ((htmlBody == null || htmlBody.isBlank()) && (textBody == null || textBody.isBlank())) {
            throw new IllegalStateException("Rendered email body is blank for template key " + template.templateKey());
        }

        return new RenderedTemplate(template.templateKey(), template.origin(), subject, htmlBody, textBody);
    }

    private String renderTemplate(String templateContent, Map<String, Object> renderData) {
        if (templateContent == null || templateContent.isBlank()) {
            return null;
        }
        Template template = engine.parse(templateContent);
        TemplateInstance instance = template.instance();
        for (Map.Entry<String, Object> entry : renderData.entrySet()) {
            instance.data(entry.getKey(), entry.getValue());
        }
        return instance.render();
    }

    private Map<String, Object> toRenderData(Object context) {
        Map<String, Object> renderData = new LinkedHashMap<>();
        if (context == null) {
            return renderData;
        }
        if (context instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    renderData.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        } else {
            try {
                renderData.putAll(objectMapper.convertValue(context, new TypeReference<LinkedHashMap<String, Object>>() {
                }));
            } catch (IllegalArgumentException ignored) {
                // Keep only the named root object if the context is not map-convertible.
            }
            addBeanProperties(renderData, context);
        }
        renderData.put("context", context);
        return renderData;
    }

    private void addBeanProperties(Map<String, Object> renderData, Object context) {
        Class<?> contextClass = context.getClass();
        if (contextClass.isRecord()) {
            for (RecordComponent component : contextClass.getRecordComponents()) {
                Method accessor = component.getAccessor();
                try {
                    renderData.put(component.getName(), accessor.invoke(context));
                } catch (Exception ignored) {
                    // Ignore inaccessible or failing accessors and keep any existing mapped values.
                }
            }
            return;
        }

        try {
            for (PropertyDescriptor descriptor : Introspector.getBeanInfo(contextClass).getPropertyDescriptors()) {
                Method readMethod = descriptor.getReadMethod();
                if (readMethod == null || "class".equals(descriptor.getName())) {
                    continue;
                }
                try {
                    renderData.put(descriptor.getName(), readMethod.invoke(context));
                } catch (Exception ignored) {
                    // Ignore individual property access failures.
                }
            }
        } catch (Exception ignored) {
            // Ignore bean introspection failures and keep any existing mapped values.
        }
    }
}
