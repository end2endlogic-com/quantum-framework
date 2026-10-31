package com.e2eq.framework.bootstrap.runtime;

import com.e2eq.framework.bootstrap.model.BootstrapPackDefinition;
import com.e2eq.framework.bootstrap.spi.BootstrapPackContributor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.io.IOException;
import java.util.*;

/** Loads existing typed pack definitions from explicit application-owned classpath resources. */
@ApplicationScoped
@io.quarkus.arc.properties.IfBuildProperty(name="quantum.tenant-initialization.enabled", stringValue="true")
public class ResourceBootstrapPackContributor implements BootstrapPackContributor {
    @Inject ObjectMapper mapper;
    @ConfigProperty(name="quantum.bootstrap.pack-resources") Optional<List<String>> resources;
    @Override public List<BootstrapPackDefinition> bootstrapPacks() {
        List<BootstrapPackDefinition> result = new ArrayList<>();
        for (String name : resources.orElse(List.of())) {
            try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(name)) {
                if (input == null) throw new IllegalStateException("BOOTSTRAP_PACK_RESOURCE_MISSING: " + name);
                List<BootstrapPackDefinition> definitions = mapper.readerFor(
                    mapper.getTypeFactory().constructCollectionType(List.class,BootstrapPackDefinition.class))
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(input);
                result.addAll(definitions);
            } catch (IOException e) { throw new IllegalStateException("BOOTSTRAP_PACK_RESOURCE_INVALID: " + name,e); }
        }
        if (result.stream().map(BootstrapPackDefinition::packRef).distinct().count() != result.size())
            throw new IllegalStateException("BOOTSTRAP_PACK_RESOURCE_DUPLICATE");
        return List.copyOf(result);
    }
}
