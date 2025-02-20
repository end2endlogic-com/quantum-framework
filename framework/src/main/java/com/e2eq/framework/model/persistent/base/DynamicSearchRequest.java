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
    protected final DynamicAttributeSet systemFields = new DynamicAttributeSet();
    protected final DynamicAttributeSet searchFields = new DynamicAttributeSet();
    @Builder.Default
    protected boolean caseInsensitive = false;
    @Builder.Default
    protected boolean exactMatches = true;
    @Builder.Default
    protected int pageNumber = 1;
    @Builder.Default
    protected int pageSize = 10;
    @Builder.Default
    @SuppressWarnings("unchecked")
    protected List<SortParameter> sortFields = Collections.EMPTY_LIST;
    @Builder.Default
    protected SearchCondition searchCondition = SearchCondition.AND;
}
