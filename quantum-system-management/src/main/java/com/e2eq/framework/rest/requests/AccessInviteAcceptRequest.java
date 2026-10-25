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
    @ToString.Exclude
    protected String token;
    /**
     * Optional echo of the invited user ID. The account identity always comes from the invite;
     * a value that differs from it is refused with INVITE_IDENTITY_MISMATCH. New clients should omit it.
     */
    protected String userId;
    /**
     * Optional echo of the invited email. A value that differs from the invite is refused with
     * INVITE_IDENTITY_MISMATCH. New clients should omit it.
     */
    protected String email;
    protected String firstName;
    protected String lastName;
    @ToString.Exclude
    protected String password;
}
