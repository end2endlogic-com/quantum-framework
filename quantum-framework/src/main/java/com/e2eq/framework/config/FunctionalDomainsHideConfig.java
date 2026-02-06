package com.e2eq.framework.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * YAML-configurable list of functional area/domain/action entries to hide from
 * functional domain discovery APIs (e.g. GET /system/permissions/entities and GET /system/permissions/fd).
 * <p>
 * Three levels of hiding (case-insensitive matching):
 * <ul>
 *   <li><b>hideAreas</b> – whole functional area (all domains and actions under it are hidden)</li>
 *   <li><b>hideAreaDomains</b> – specific area/domain pair (all actions under that pair are hidden)</li>
 *   <li><b>hideActions</b> – specific area/domain/action (only that action is hidden)</li>
 * </ul>
 * Example YAML:
 * <pre>
 * hideAreas:
 *   - "internal"
 * hideAreaDomains:
 *   - area: "ai"
 *     domain: "tools"
 * hideActions:
 *   - area: "system"
 *     domain: "permission"
 *     action: "roleProvenance"
 * </pre>
 *
 * @see FunctionalDomainsHideFilter
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FunctionalDomainsHideConfig {

    /** Functional area names to hide entirely (all domains under that area). */
    private List<String> hideAreas = new ArrayList<>();

    /** Area/domain pairs to hide (all actions under that pair). */
    private List<AreaDomainEntry> hideAreaDomains = new ArrayList<>();

    /** Specific area/domain/action entries to hide. */
    private List<AreaDomainActionEntry> hideActions = new ArrayList<>();

    public List<String> getHideAreas() {
        return hideAreas;
    }

    public void setHideAreas(List<String> hideAreas) {
        this.hideAreas = hideAreas != null ? hideAreas : new ArrayList<>();
    }

    public List<AreaDomainEntry> getHideAreaDomains() {
        return hideAreaDomains;
    }

    public void setHideAreaDomains(List<AreaDomainEntry> hideAreaDomains) {
        this.hideAreaDomains = hideAreaDomains != null ? hideAreaDomains : new ArrayList<>();
    }

    public List<AreaDomainActionEntry> getHideActions() {
        return hideActions;
    }

    public void setHideActions(List<AreaDomainActionEntry> hideActions) {
        this.hideActions = hideActions != null ? hideActions : new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AreaDomainEntry {
        private String area;
        private String domain;

        public String getArea() {
            return area;
        }

        public void setArea(String area) {
            this.area = area;
        }

        public String getDomain() {
            return domain;
        }

        public void setDomain(String domain) {
            this.domain = domain;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AreaDomainActionEntry {
        private String area;
        private String domain;
        private String action;

        public String getArea() {
            return area;
        }

        public void setArea(String area) {
            this.area = area;
        }

        public String getDomain() {
            return domain;
        }

        public void setDomain(String domain) {
            this.domain = domain;
        }

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }
    }
}
