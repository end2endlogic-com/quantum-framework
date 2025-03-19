package com.e2eq.framework.rest.models;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
@EqualsAndHashCode
@ToString
public class ChangePasswordRequest {

    protected String oldPassword;

    @NotNull
    @Size(min=8, max = 50, message="password length must be less than or equal to 50 and greater than 8 characters")
    protected String newPassword;
    @NotNull
    protected String confirmPassword;
    @NotNull
    protected String userId;
    protected String passwordHint;
    protected boolean forgotPassword = false;
    protected String authorizationToken;
}
