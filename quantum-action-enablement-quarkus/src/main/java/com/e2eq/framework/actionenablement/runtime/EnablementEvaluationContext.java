package com.e2eq.framework.actionenablement.runtime;

import com.e2eq.framework.actionenablement.model.ScopedActionRef;
import com.e2eq.framework.model.persistent.base.DataDomain;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnablementEvaluationContext {

    private String identity;
    private String realm;
    private String[] roles;
    private String scope;
    private DataDomain dataDomain;
    private ScopedActionRef scopedAction;
}
