package com.e2eq.framework.actionenablement.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ScopedActionEnablementRequest {

    private String identity;
    private String realm;
    private String[] roles;

    private String orgRefName;
    private String accountNumber;
    private String tenantId;
    private Integer dataSegment;
    private String ownerId;
    private String scope;

    @Builder.Default
    private List<ScopedActionRef> actions = new ArrayList<>();
}
