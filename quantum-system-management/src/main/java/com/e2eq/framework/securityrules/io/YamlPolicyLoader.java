package com.e2eq.framework.securityrules.io;

import com.e2eq.framework.model.security.Policy;
import com.e2eq.framework.model.security.Rule;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

public final class YamlPolicyLoader {

    private final ObjectMapper mapper;

    public YamlPolicyLoader() {
        this.mapper = new ObjectMapper(new YAMLFactory())
                .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
    }

    public List<YamlPolicyItem> load(Path yamlPath) throws IOException {
        if (yamlPath == null || !Files.exists(yamlPath)) {
            return Collections.emptyList();
        }
        try (InputStream is = Files.newInputStream(yamlPath)) {
            return load(is);
        }
    }

    public List<YamlPolicyItem> load(InputStream yamlStream) throws IOException {
        if (yamlStream == null) {
            return Collections.emptyList();
        }
        byte[] data = yamlStream.readAllBytes();
        if (data.length == 0) {
            return Collections.emptyList();
        }

        IOException lastError = null;
        boolean parsed = false;

        // Structured format: { policies: [ ... ] }
        try (InputStream structured = new ByteArrayInputStream(data)) {
            YamlPolicyFile file = mapper.readValue(structured, YamlPolicyFile.class);
            parsed = true;
            if (file != null && file.policies != null && !file.policies.isEmpty()) {
                return file.policies;
            }
        } catch (IOException ex) {
            lastError = ex;
        }

        // Direct list of YamlPolicyItem definitions
        try (InputStream direct = new ByteArrayInputStream(data)) {
            CollectionType yamlList = mapper.getTypeFactory()
                    .constructCollectionType(ArrayList.class, YamlPolicyItem.class);
            List<YamlPolicyItem> items = mapper.readValue(direct, yamlList);
            parsed = true;
            if (items != null && !items.isEmpty()) {
                return items;
            }
        } catch (IOException ex) {
            lastError = ex;
        }

        // Legacy format: List<Policy> with embedded Rule definitions
        try (InputStream legacy = new ByteArrayInputStream(data)) {
            CollectionType policyList = mapper.getTypeFactory()
                    .constructCollectionType(ArrayList.class, Policy.class);
            List<Policy> policies = mapper.readValue(legacy, policyList);
            parsed = true;
            if (policies != null && !policies.isEmpty()) {
                List<YamlPolicyItem> converted = new ArrayList<>(policies.size());
                for (Policy p : policies) {
                    if (p == null) continue;
                    YamlPolicyItem item = new YamlPolicyItem();
                    item.refName = p.getRefName();
                    item.displayName = p.getDisplayName();
                    item.description = p.getDescription();
                    item.principalId = p.getPrincipalId();
                    item.principalType = p.getPrincipalType() != null ? p.getPrincipalType().name() : null;
                    if (p.getRules() != null && !p.getRules().isEmpty()) {
                        List<Rule> copy = new ArrayList<>();
                        for (Rule r : p.getRules()) {
                            if (r != null) {
                                copy.add(r);
                            }
                        }
                        if (!copy.isEmpty()) {
                            item.legacyRules = copy;
                        }
                    }
                    converted.add(item);
                }
                return converted;
            }
        } catch (IOException ex) {
            lastError = ex;
        }

        if (!parsed && lastError != null) {
            throw lastError;
        }

        return Collections.emptyList();
    }

    public List<YamlPolicyItem> load(String yamlContent) throws IOException {
        if (yamlContent == null || yamlContent.isBlank()) {
            return Collections.emptyList();
        }
        try (InputStream is = new ByteArrayInputStream(yamlContent.getBytes(StandardCharsets.UTF_8))) {
            return load(is);
        }
    }
}
