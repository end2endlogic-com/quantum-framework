package com.e2eq.tools;

import com.e2eq.framework.model.security.Rule;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Small CLI utility to convert a YAML array of security Rules into JSONL (one JSON object per line).
 *
 * Usage:
 *   java com.e2eq.tools.RulesYamlToJsonl <input.yaml> <output.jsonl>
 *
 * Example (from repo root, using the existing test YAML):
 *   - input:  quantum-framework/src/test/resources/securityRules.yaml
 *   - output: target/securityRules.jsonl
 */
public final class RulesYamlToJsonl {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: RulesYamlToJsonl <input.yaml> <output.jsonl>");
            System.exit(1);
        }

        Path input = Paths.get(args[0]);
        Path output = Paths.get(args[1]);

        if (!Files.exists(input)) {
            System.err.printf("Input file does not exist: %s%n", input.toAbsolutePath());
            System.exit(2);
        }

        // 1) Read YAML into List<Rule>
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        CollectionType listType = yamlMapper.getTypeFactory()
                .constructCollectionType(ArrayList.class, Rule.class);
        List<Rule> rules;
        try {
            rules = yamlMapper.readValue(Files.newInputStream(input), listType);
        } catch (IOException e) {
            System.err.printf("Failed to read/parse YAML from %s: %s%n", input.toAbsolutePath(), e.getMessage());
            throw e;
        }

        // 2) Write JSONL: one Rule per line
        ObjectMapper jsonMapper = new ObjectMapper();
        try (BufferedWriter w = Files.newBufferedWriter(
                output,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            for (Rule r : rules) {
                w.write(jsonMapper.writeValueAsString(r));
                w.write('\n');
            }
        }

        System.out.printf("Wrote %d rules to %s%n", rules.size(), output.toAbsolutePath());
    }
}
