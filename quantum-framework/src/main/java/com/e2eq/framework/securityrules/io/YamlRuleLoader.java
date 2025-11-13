package com.e2eq.framework.securityrules.io;

import com.e2eq.framework.model.security.Rule;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.type.CollectionType;

public final class YamlRuleLoader {

    private final ObjectMapper mapper;

    public YamlRuleLoader() {
        this.mapper = new ObjectMapper(new YAMLFactory())
                .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
    }

    public List<Rule> load(Path yamlPath) throws IOException {
        if (yamlPath == null || !Files.exists(yamlPath)) return Collections.emptyList();
        try (InputStream is = Files.newInputStream(yamlPath)) {
            return load(is);
        }
    }

    public List<Rule> load(InputStream yamlStream) throws IOException {
        if (yamlStream == null) return Collections.emptyList();
        byte[] data = yamlStream.readAllBytes();
        if (data.length == 0) {
            return Collections.emptyList();
        }

        // Attempt structured rule format first (rules array with header axes)
        try (InputStream structured = new ByteArrayInputStream(data)) {
            YamlRuleFile file = mapper.readValue(structured, YamlRuleFile.class);
            List<Rule> converted = YamlRuleMapper.toRules(file != null ? file.rules : null);
            if (!converted.isEmpty()) {
                return converted;
            }
        } catch (IOException ignore) {
            // fall through to legacy parsing
        }

        // Fallback to legacy List<Rule> format
        try (InputStream legacy = new ByteArrayInputStream(data)) {
            CollectionType listType = mapper.getTypeFactory()
                    .constructCollectionType(ArrayList.class, Rule.class);
            return mapper.readValue(legacy, listType);
        }
    }
}
