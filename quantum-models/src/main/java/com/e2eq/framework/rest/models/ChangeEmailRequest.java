package com.e2eq.framework.rest.models;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
@EqualsAndHashCode
@ToString
public class ChangeEmailRequest {

    @NotNull
    @Email
    protected String currentEmail;

    @NotNull
    @Email
    protected String newEmail;

    protected String authProvider;
}
