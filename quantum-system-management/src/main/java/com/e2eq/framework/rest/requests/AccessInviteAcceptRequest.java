package com.e2eq.framework.rest.requests;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@Data
@EqualsAndHashCode
@SuperBuilder
@ToString
@NoArgsConstructor
public class AccessInviteAcceptRequest {
    protected String realm;
    protected String token;
    protected String userId;
    protected String email;
    protected String firstName;
    protected String lastName;
    protected String password;
}
