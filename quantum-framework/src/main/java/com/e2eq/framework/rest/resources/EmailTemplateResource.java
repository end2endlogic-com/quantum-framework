package com.e2eq.framework.rest.resources;

import com.e2eq.framework.mail.EmailTemplateDefinition;
import com.e2eq.framework.mail.EmailTemplateRenderService;
import com.e2eq.framework.mail.RenderedTemplate;
import com.e2eq.framework.model.persistent.email.EmailTemplate;
import com.e2eq.framework.model.persistent.morphia.EmailTemplateRepo;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.rest.requests.EmailTemplatePreviewRequest;
import com.e2eq.framework.rest.requests.EmailTemplateUpsertRequest;
import com.e2eq.framework.rest.responses.EmailTemplateDetailResponse;
import com.e2eq.framework.rest.responses.EmailTemplateListResponse;
import com.e2eq.framework.rest.responses.EmailTemplatePreviewResponse;
import com.e2eq.framework.security.runtime.RuleContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

import java.util.List;
import java.util.Optional;

@Path("/settings/email-templates")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EmailTemplateResource {

    @Inject
    EmailTemplateRepo emailTemplateRepo;

    @Inject
    EmailTemplateRenderService emailTemplateRenderService;

    @Inject
    RuleContext ruleContext;

    private String getRealmId() {
        Optional<PrincipalContext> pc = SecurityContext.getPrincipalContext();
        Optional<ResourceContext> rc = SecurityContext.getResourceContext();
        if (pc.isEmpty() || rc.isEmpty()) {
            return null;
        }
        return ruleContext.getRealmId(pc.get(), rc.get());
    }

    @GET
    @Operation(summary = "List email templates")
    @SecurityRequirement(name = "bearerAuth")
    public Response list() {
        String realm = getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        List<EmailTemplateListResponse> responses = emailTemplateRepo.getAllList(realm).stream()
            .map(this::toListResponse)
            .toList();
        return Response.ok(responses).build();
    }

    @GET
    @Path("{refName}")
    @Operation(summary = "Get email template")
    @SecurityRequirement(name = "bearerAuth")
    public Response get(@PathParam("refName") String refName) {
        String realm = getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        EmailTemplate template = emailTemplateRepo.findByRefName(refName, realm)
            .orElseThrow(() -> new NotFoundException("Email template not found: " + refName));
        return Response.ok(toDetailResponse(template)).build();
    }

    @POST
    @Operation(summary = "Create email template")
    @SecurityRequirement(name = "bearerAuth")
    public Response create(EmailTemplateUpsertRequest request) {
        String realm = getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        validateRequest(request, true);

        String refName = normalize(request.getRefName());
        if (refName == null) {
            refName = normalize(request.getTemplateKey()).replace('.', '-');
        }
        if (emailTemplateRepo.findByRefName(refName, realm).isPresent()) {
            return Response.status(Response.Status.CONFLICT)
                .entity("Email template already exists: " + refName)
                .build();
        }
        String templateKey = normalize(request.getTemplateKey());
        Optional<EmailTemplate> existingByKey = emailTemplateRepo.findByTemplateKey(realm, templateKey);
        if (existingByKey.isPresent()) {
            return Response.status(Response.Status.CONFLICT)
                .entity("Email template key already exists: " + templateKey)
                .build();
        }

        EmailTemplate template = new EmailTemplate();
        template.setRefName(refName);
        applyRequest(template, request);
        if (template.getDisplayName() == null) {
            template.setDisplayName(template.getTemplateKey());
        }
        emailTemplateRepo.save(realm, template);
        return Response.status(Response.Status.CREATED).entity(toDetailResponse(template)).build();
    }

    @PUT
    @Path("{refName}")
    @Operation(summary = "Update email template")
    @SecurityRequirement(name = "bearerAuth")
    public Response update(@PathParam("refName") String refName, EmailTemplateUpsertRequest request) {
        String realm = getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        validateRequest(request, false);

        EmailTemplate template = emailTemplateRepo.findByRefName(refName, realm)
            .orElseThrow(() -> new NotFoundException("Email template not found: " + refName));

        String requestedTemplateKey = normalize(request.getTemplateKey());
        if (requestedTemplateKey != null && !requestedTemplateKey.equals(template.getTemplateKey())) {
            Optional<EmailTemplate> existingByKey = emailTemplateRepo.findByTemplateKey(realm, requestedTemplateKey);
            if (existingByKey.isPresent() && !refName.equals(existingByKey.get().getRefName())) {
                return Response.status(Response.Status.CONFLICT)
                    .entity("Email template key already exists: " + requestedTemplateKey)
                    .build();
            }
        }

        applyRequest(template, request);
        emailTemplateRepo.save(realm, template);
        return Response.ok(toDetailResponse(template)).build();
    }

    @DELETE
    @Path("{refName}")
    @Operation(summary = "Delete email template")
    @SecurityRequirement(name = "bearerAuth")
    public Response delete(@PathParam("refName") String refName) {
        String realm = getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        boolean deleted = emailTemplateRepo.deleteByRefName(realm, refName);
        if (!deleted) {
            throw new NotFoundException("Email template not found: " + refName);
        }
        return Response.noContent().build();
    }

    @POST
    @Path("preview")
    @Operation(summary = "Preview email template rendering")
    @SecurityRequirement(name = "bearerAuth")
    public Response preview(EmailTemplatePreviewRequest request) {
        String realm = getRealmId();
        if (realm == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (request == null) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        try {
            RenderedTemplate rendered;
            if (hasInlineTemplateContent(request)) {
                String templateKey = normalize(request.getTemplateKey());
                if (templateKey == null) {
                    templateKey = "preview";
                }
                rendered = emailTemplateRenderService.render(new EmailTemplateDefinition(
                    templateKey,
                    request.getSubjectTemplate(),
                    request.getHtmlTemplate(),
                    request.getTextTemplate(),
                    EmailTemplateDefinition.Origin.REALM_DB
                ), request.getContext());
            } else {
                String templateKey = normalize(request.getTemplateKey());
                if (templateKey == null) {
                    throw new BadRequestException("templateKey is required for preview when no inline templates are provided.");
                }
                rendered = emailTemplateRenderService.render(realm, templateKey, request.getContext());
            }

            return Response.ok(EmailTemplatePreviewResponse.builder()
                .templateKey(rendered.templateKey())
                .origin(rendered.origin().name())
                .subject(rendered.subject())
                .htmlBody(rendered.htmlBody())
                .textBody(rendered.textBody())
                .build()).build();
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Email template not found:")) {
                throw new NotFoundException(e.getMessage());
            }
            throw new BadRequestException(e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new BadRequestException(e.getMessage(), e);
        }
    }

    private void validateRequest(EmailTemplateUpsertRequest request, boolean creating) {
        if (request == null) {
            throw new BadRequestException("Request body is required.");
        }
        if (creating && normalize(request.getTemplateKey()) == null) {
            throw new BadRequestException("templateKey is required.");
        }
    }

    private boolean hasInlineTemplateContent(EmailTemplatePreviewRequest request) {
        return normalize(request.getSubjectTemplate()) != null
            || normalize(request.getHtmlTemplate()) != null
            || normalize(request.getTextTemplate()) != null;
    }

    private void applyRequest(EmailTemplate template, EmailTemplateUpsertRequest request) {
        if (normalize(request.getDisplayName()) != null) {
            template.setDisplayName(request.getDisplayName().trim());
        }
        if (normalize(request.getTemplateKey()) != null) {
            template.setTemplateKey(request.getTemplateKey().trim());
        }
        if (request.getDescription() != null) {
            template.setDescription(request.getDescription());
        }
        if (request.getActive() != null) {
            template.setActive(request.getActive());
        }
        if (normalize(request.getSourceType()) != null) {
            try {
                template.setSourceType(EmailTemplate.SourceType.valueOf(request.getSourceType().trim()));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Unsupported sourceType: " + request.getSourceType());
            }
        }
        if (request.getClasspathTemplateBaseName() != null) {
            template.setClasspathTemplateBaseName(request.getClasspathTemplateBaseName());
        }
        if (request.getFunctionalArea() != null) {
            template.setFunctionalArea(request.getFunctionalArea());
        }
        if (request.getSubjectTemplate() != null) {
            template.setSubjectTemplate(request.getSubjectTemplate());
        }
        if (request.getHtmlTemplate() != null) {
            template.setHtmlTemplate(request.getHtmlTemplate());
        }
        if (request.getTextTemplate() != null) {
            template.setTextTemplate(request.getTextTemplate());
        }
        if (request.getSampleContextJson() != null) {
            template.setSampleContextJson(request.getSampleContextJson());
        }
        if (request.getSchemaVersion() != null) {
            template.setSchemaVersion(request.getSchemaVersion());
        }
    }

    private EmailTemplateListResponse toListResponse(EmailTemplate template) {
        return EmailTemplateListResponse.builder()
            .refName(template.getRefName())
            .displayName(template.getDisplayName())
            .templateKey(template.getTemplateKey())
            .description(template.getDescription())
            .active(template.isActive())
            .sourceType(template.getSourceType() != null ? template.getSourceType().name() : null)
            .functionalArea(template.getFunctionalArea())
            .build();
    }

    private EmailTemplateDetailResponse toDetailResponse(EmailTemplate template) {
        return EmailTemplateDetailResponse.builder()
            .refName(template.getRefName())
            .displayName(template.getDisplayName())
            .templateKey(template.getTemplateKey())
            .description(template.getDescription())
            .active(template.isActive())
            .sourceType(template.getSourceType() != null ? template.getSourceType().name() : null)
            .classpathTemplateBaseName(template.getClasspathTemplateBaseName())
            .functionalArea(template.getFunctionalArea())
            .subjectTemplate(template.getSubjectTemplate())
            .htmlTemplate(template.getHtmlTemplate())
            .textTemplate(template.getTextTemplate())
            .sampleContextJson(template.getSampleContextJson())
            .schemaVersion(template.getSchemaVersion())
            .build();
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
