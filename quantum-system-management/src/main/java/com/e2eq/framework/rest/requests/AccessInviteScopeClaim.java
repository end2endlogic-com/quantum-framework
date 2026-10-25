package com.e2eq.framework.rest.requests;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A partition an invite request claims. It can never choose the invite's scope: the scope is
 * always the inviter's partition resolved from its verified principal context. A claim is accepted
 * only when every field it sets equals that resolved partition; any difference is refused with
 * {@code 403 INVITE_SCOPE_NOT_AUTHORIZED}. Unset (null) fields are not compared.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccessInviteScopeClaim {
    private String realm;
    private String tenantId;
    private String orgRefName;
    private String accountNum;
    private Integer dataSegment;
    private String locationId;
    private String businessTransactionId;
}
