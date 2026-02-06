package com.e2eq.framework.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads the functional-domains hide configuration from YAML and answers whether
 * a given area, area/domain, or area/domain/action should be hidden from functional
 * domain discovery APIs.
 * <p>
 * Config is loaded from classpath or file path (see {@code quantum.functional-domains.hide-config}).
 * Matching is case-insensitive.
 *
 * @see FunctionalDomainsHideConfig
 * @see com.e2eq.framework.rest.resources.PermissionResource
 */
@ApplicationScoped
public class FunctionalDomainsHideFilter {

    private static final String DEFAULT_CLASSPATH = "functional-domains-hide.yaml";

    @ConfigProperty(name = "quantum.functional-domains.hide-config", defaultValue = DEFAULT_CLASSPATH)
    String configPath;

    private volatile FunctionalDomainsHideConfig config;
    private volatile long fileLastModified;

    /**
     * Returns true if the entire functional area should be hidden (all domains under it).
     */
    public boolean isAreaHidden(String area) {
        if (area == null || area.isBlank()) return false;
        FunctionalDomainsHideConfig c = loadConfig();
        if (c.getHideAreas() == null || c.getHideAreas().isEmpty()) return false;
        String normalized = area.trim();
        for (String a : c.getHideAreas()) {
            if (a != null && a.trim().equalsIgnoreCase(normalized)) return true;
        }
        return false;
    }

    /**
     * Returns true if the area is hidden entirely, or the specific area/domain pair is hidden.
     */
    public boolean isAreaDomainHidden(String area, String domain) {
        if (area == null || area.isBlank()) return false;
        if (isAreaHidden(area)) return true;
        if (domain == null || domain.isBlank()) return false;
        FunctionalDomainsHideConfig c = loadConfig();
        if (c.getHideAreaDomains() == null || c.getHideAreaDomains().isEmpty()) return false;
        String na = area.trim();
        String nd = domain.trim();
        for (FunctionalDomainsHideConfig.AreaDomainEntry e : c.getHideAreaDomains()) {
            if (e != null && matches(e.getArea(), na) && matches(e.getDomain(), nd)) return true;
        }
        return false;
    }

    /**
     * Returns true if the area/domain is hidden (whole area, or whole area/domain), or the specific action is hidden.
     */
    public boolean isActionHidden(String area, String domain, String action) {
        if (isAreaDomainHidden(area, domain)) return true;
        if (action == null || action.isBlank()) return false;
        FunctionalDomainsHideConfig c = loadConfig();
        if (c.getHideActions() == null || c.getHideActions().isEmpty()) return false;
        String na = area != null ? area.trim() : "";
        String nd = domain != null ? domain.trim() : "";
        String nact = action.trim();
        for (FunctionalDomainsHideConfig.AreaDomainActionEntry e : c.getHideActions()) {
            if (e != null && matches(e.getArea(), na) && matches(e.getDomain(), nd) && matches(e.getAction(), nact))
                return true;
        }
        return false;
    }

    /**
     * Filters a set of actions by removing any that are hidden for the given area/domain.
     */
    public Set<String> filterHiddenActions(String area, String domain, Set<String> actions) {
        if (actions == null || actions.isEmpty()) return actions;
        Set<String> out = new HashSet<>();
        for (String action : actions) {
            if (!isActionHidden(area, domain, action)) out.add(action);
        }
        return out;
    }

    private static boolean matches(String configVal, String value) {
        if (configVal == null || configVal.isBlank()) return value == null || value.isBlank();
        return configVal.trim().equalsIgnoreCase(value);
    }

    private FunctionalDomainsHideConfig loadConfig() {
        if (configPath == null || configPath.isBlank()) {
            return emptyConfig();
        }
        String path = configPath.trim();
        Path file = Path.of(path);
        if (Files.isRegularFile(file)) {
            try {
                long mod = Files.getLastModifiedTime(file).toMillis();
                if (config != null && mod == fileLastModified) return config;
                try (InputStream in = Files.newInputStream(file)) {
                    config = parse(in);
                    fileLastModified = mod;
                    return config;
                }
            } catch (Exception e) {
                Log.warnf(e, "Failed to load functional-domains hide config from file %s", path);
                return config != null ? config : emptyConfig();
            }
        }
        if (config != null) return config;
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = getClass().getClassLoader();
        String resource = path.startsWith("/") ? path.substring(1) : path;
        try (InputStream in = cl.getResourceAsStream(resource)) {
            if (in != null) {
                config = parse(in);
                return config;
            }
        } catch (Exception e) {
            Log.debugf(e, "Could not load functional-domains hide config from classpath %s", resource);
        }
        config = emptyConfig();
        return config;
    }

    private static FunctionalDomainsHideConfig parse(InputStream in) throws java.io.IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        FunctionalDomainsHideConfig c = mapper.readValue(in, FunctionalDomainsHideConfig.class);
        return c != null ? c : emptyConfig();
    }

    private static FunctionalDomainsHideConfig emptyConfig() {
        FunctionalDomainsHideConfig c = new FunctionalDomainsHideConfig();
        c.setHideAreas(List.of());
        c.setHideAreaDomains(List.of());
        c.setHideActions(List.of());
        return c;
    }
}
