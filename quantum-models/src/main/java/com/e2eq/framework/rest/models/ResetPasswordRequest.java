package com.e2eq.framework.rest.models;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
@EqualsAndHashCode
@ToString
public class ResetPasswordRequest {

    @NotNull
    protected String userId;

    @NotNull
    @Size(min = 8, max = 50, message = "password length must be less than or equal to 50 and greater than 8 characters")
    protected String newPassword;

    @NotNull
    protected String confirmPassword;

    protected Boolean forceChangePassword = Boolean.FALSE;

    protected String authProvider;
}
