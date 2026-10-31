package com.e2eq.framework.rest.resources;

import com.e2eq.framework.annotations.FunctionalAction;
import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.model.persistent.email.EmailTemplate;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Without a resource-level mapping and per-method actions, SecurityFilter gives /settings/email-templates an
 * anonymous ResourceContext (action=none) and PermissionRuleInterceptor rejects every save.
 */
class EmailTemplateResourceMappingTest {

    private static final Set<String> WRITE_ACTIONS = Set.of("CREATE", "SAVE", "UPDATE", "DELETE", "APPLY", "WRITE");

    @Test
    void theResourceIsMappedLikeItsModel() {
        FunctionalMapping m = EmailTemplateResource.class.getAnnotation(FunctionalMapping.class);
        assertNotNull(m, "EmailTemplateResource needs a @FunctionalMapping");
        EmailTemplate model = new EmailTemplate();
        assertEquals(model.bmFunctionalArea(), m.area());
        assertEquals(model.bmFunctionalDomain(), m.domain());
    }

    @Test
    void everyEndpointDeclaresAnActionAndWritesDeclareAWriteAction() {
        for (Method method : EmailTemplateResource.class.getDeclaredMethods()) {
            boolean endpoint = method.isAnnotationPresent(GET.class) || method.isAnnotationPresent(POST.class)
                    || method.isAnnotationPresent(PUT.class) || method.isAnnotationPresent(DELETE.class);
            if (!endpoint) {
                continue;
            }
            FunctionalAction action = method.getAnnotation(FunctionalAction.class);
            assertNotNull(action, method.getName() + " needs a @FunctionalAction");
            boolean writes = Set.of("create", "update", "delete").contains(method.getName());
            if (writes) {
                assertTrue(WRITE_ACTIONS.contains(action.value().toUpperCase()),
                        method.getName() + " writes, so its action must be one PermissionRuleInterceptor accepts");
            }
        }
    }
}
