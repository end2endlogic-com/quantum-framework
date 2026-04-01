package com.e2eq.framework.actionenablement.runtime;

import com.e2eq.framework.actionenablement.model.EnablementBlocker;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DependencyResolutionResult {

    private boolean satisfied;

    @Builder.Default
    private List<EnablementBlocker> blockers = new ArrayList<>();

    public static DependencyResolutionResult satisfied() {
        return DependencyResolutionResult.builder()
                .satisfied(true)
                .build();
    }

    public static DependencyResolutionResult blocked(EnablementBlocker blocker) {
        return DependencyResolutionResult.builder()
                .satisfied(false)
                .blockers(new ArrayList<>(List.of(blocker)))
                .build();
    }

    public static DependencyResolutionResult blocked(List<EnablementBlocker> blockers) {
        return DependencyResolutionResult.builder()
                .satisfied(false)
                .blockers(new ArrayList<>(blockers))
                .build();
    }
}
