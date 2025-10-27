package com.e2eq.ontology.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Data transfer object representing a JointJS friendly view of an ontology.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OntologyVisualizationPayload(
        List<Node> nodes,
        List<Link> links,
        List<Rule> rules
) {

    public OntologyVisualizationPayload {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        links = links == null ? List.of() : List.copyOf(links);
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Node(
            String id,
            String type,
            List<String> parents,
            List<String> disjointWith,
            List<String> sameAs
    ) {
        public Node {
            parents = parents == null ? List.of() : List.copyOf(parents);
            disjointWith = disjointWith == null ? List.of() : List.copyOf(disjointWith);
            sameAs = sameAs == null ? List.of() : List.copyOf(sameAs);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Link(
            String id,
            String source,
            String target,
            String kind,
            String domain,
            String range,
            Boolean inverse,
            String inverseOf,
            Boolean transitive,
            List<List<String>> rules
    ) {
        public Link {
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Rule(
            List<String> chain,
            String implies
    ) {
        public Rule {
            chain = chain == null ? List.of() : List.copyOf(chain);
        }
    }
}
