package com.e2eq.framework.rest.models;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.Map;

@Data
@RegisterForReflection
@EqualsAndHashCode
@ToString
public class DeployedVersion {
    // key = the component name, value = the full version of the component including build number
    Map<String, ComponentVersion> componentVersions;
}
