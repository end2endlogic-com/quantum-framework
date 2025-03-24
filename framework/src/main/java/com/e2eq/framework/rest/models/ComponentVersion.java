package com.e2eq.framework.rest.models;

import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.Date;
import java.util.List;

@Data
@RegisterForReflection
@EqualsAndHashCode
@SuperBuilder
@NoArgsConstructor
public class ComponentVersion {
    String componentName;
    int major;
    int minor;
    List<String> suffix;
    int patch;
    String buildNumber;
    Date timestamp;
    String strictVersionString;

    String createSuffixString() {
        StringBuilder sb = new StringBuilder();
        if (suffix != null) {
            for (String s : suffix) {
                sb.append("-").append(s);
            }
        }
        return sb.toString();
    }

    public String getFullVersionString() {
        return String.format( "%d.%d.%d%s%s",
                major,
                minor,
                patch,
                (suffix== null || suffix.isEmpty()) ? "" :  createSuffixString(), (buildNumber==null) ? "" : "+"+ buildNumber);
    }

    @Override
    public String toString() {
        return String.format("%s: %s", componentName, getFullVersionString());
    }
}
