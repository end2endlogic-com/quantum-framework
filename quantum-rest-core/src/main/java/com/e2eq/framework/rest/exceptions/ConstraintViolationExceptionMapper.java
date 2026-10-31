package com.e2eq.framework.rest.exceptions;
import java.util.Map;
import java.util.LinkedHashMap;
import com.e2eq.framework.model.persistent.base.RefNameRule;
import com.e2eq.framework.exceptions.RefNameViolationException;
import com.e2eq.framework.annotations.CanonicalRefName;

import com.e2eq.framework.rest.models.RestError;
import com.e2eq.framework.util.ExceptionLoggingUtils;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import jakarta.validation.ConstraintViolationException;
import java.util.Set;

@Provider
public class ConstraintViolationExceptionMapper implements ExceptionMapper<ConstraintViolationException> {

    @Override
    public Response toResponse(ConstraintViolationException exception) {
        // Log validation errors at WARN level (not ERROR, as these are expected client errors)
        ExceptionLoggingUtils.logWarn(exception, "Constraint violation exception occurred");
        
        String stackTrace = ExceptionLoggingUtils.getStackTrace(exception);

        Set<String> violations = exception.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath().toString() + " " + violation.getMessage())
                .collect(java.util.stream.Collectors.toSet());

        RestError error = RestError.builder()
                .statusMessage("The request failed due to validation errors, correct the errors and try again.")
                .status(Response.Status.BAD_REQUEST.getStatusCode())
                .reasonMessage("A validation exception occurred")
                .debugMessage(stackTrace)
                .constraintViolations(violations)
                .build();

        // Bean validation (@Size on refName) answers before the repository's refName contract; give canonical
        // types the same typed errorCode the contract would have produced.
        exception.getConstraintViolations().stream()
                .filter(v -> v.getLeafBean() != null
                        && v.getLeafBean().getClass().isAnnotationPresent(CanonicalRefName.class)
                        && v.getPropertyPath().toString().endsWith("refName"))
                .findFirst()
                .ifPresent(v -> {
                    Map<String, String> diagnostics = new LinkedHashMap<>();
                    diagnostics.put("code", RefNameViolationException.Code.REFNAME_INVALID_FORMAT.name());
                    diagnostics.put("entityType", v.getLeafBean().getClass().getSimpleName());
                    if (v.getInvalidValue() != null) diagnostics.put("offeredRefName", String.valueOf(v.getInvalidValue()));
                    diagnostics.put("rule", RefNameRule.DESCRIPTION);
                    error.setErrorCode(RefNameViolationException.Code.REFNAME_INVALID_FORMAT.name());
                    error.setDiagnostics(diagnostics);
                });

        return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
    }
}
