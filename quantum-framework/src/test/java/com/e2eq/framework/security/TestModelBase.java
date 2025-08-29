package com.e2eq.framework.security;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode
public class TestModelBase {
   String ownerId;
   String tenantId;
   String accountId;
   int dataSegment;
   String type;
}
