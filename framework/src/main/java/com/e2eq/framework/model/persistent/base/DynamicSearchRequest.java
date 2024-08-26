package com.e2eq.framework.model.persistent.base;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

@Data
@AllArgsConstructor
@Builder
@NoArgsConstructor
public class DynamicSearchRequest {
    protected DynamicAttributeSet systemFields = new DynamicAttributeSet();
    protected DynamicAttributeSet searchFields = new DynamicAttributeSet();
    protected boolean caseInsensitive = false;
    protected boolean exactMatches = true;
    protected List<SortParameter> sortFields = Collections.EMPTY_LIST;
    protected SearchCondition searchCondition = SearchCondition.AND;
}
