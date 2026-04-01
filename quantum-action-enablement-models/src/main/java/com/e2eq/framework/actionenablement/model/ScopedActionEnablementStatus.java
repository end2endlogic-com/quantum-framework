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
public class ScopedActionEnablementStatus {

    private ScopedActionRef scopedAction;
    private boolean allowed;
    private boolean enabled;
    private boolean ready;
    private boolean usable;

    @Builder.Default
    private List<EnablementBlocker> blockers = new ArrayList<>();
}
