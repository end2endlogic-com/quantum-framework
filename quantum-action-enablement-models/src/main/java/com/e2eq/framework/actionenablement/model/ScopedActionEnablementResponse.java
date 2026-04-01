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
public class ScopedActionEnablementResponse {

    @Builder.Default
    private List<ScopedActionEnablementStatus> results = new ArrayList<>();
}
