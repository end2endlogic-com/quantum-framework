package com.e2eq.framework.rest.models;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
@EqualsAndHashCode
@ToString
public class ChangePasswordRequest {

    protected String oldPassword;

    @NotNull
    protected String newPassword;
    @NotNull
    protected String confirmPassword;
    @NotNull
    protected String userId;
    protected String passwordHint;
    protected boolean forgotPassword = false;
    protected String authorizationToken;
}
