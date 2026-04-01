package com.e2eq.framework.actionenablement.spi;

import com.e2eq.framework.actionenablement.model.DependencyCheckRef;
import com.e2eq.framework.actionenablement.runtime.DependencyResolutionResult;
import com.e2eq.framework.actionenablement.runtime.EnablementEvaluationContext;

public interface ActionDependencyResolver {

    String supportsType();

    DependencyResolutionResult evaluate(DependencyCheckRef dependency, EnablementEvaluationContext context);
}
