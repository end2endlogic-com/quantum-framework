
package com.e2eq.ontology.policy;

import java.util.*;
import java.util.function.Function;

@SuppressWarnings("unchecked")
public final class ScriptHelpers {
    private ScriptHelpers(){}

    public static void install(Map<String,Object> scriptBindings){
        Map<String,Object> rcontext = (Map<String,Object>) scriptBindings.getOrDefault("rcontext", new HashMap<>());
        List<Map<String,Object>> edges = (List<Map<String,Object>>) rcontext.getOrDefault("edges", List.of());

        scriptBindings.put("isA", (Function<String, Boolean>) t -> {
            List<String> types = (List<String>) rcontext.getOrDefault("types", List.of());
            return types.contains(t);
        });
        scriptBindings.put("hasLabel", (Function<String, Boolean>) l -> {
            List<String> labels = (List<String>) rcontext.getOrDefault("labels", List.of());
            return labels.contains(l);
        });
        scriptBindings.put("hasEdge", (Bi2<String,String,Boolean>) (p, dst) -> {
            for (Map<String,Object> e : edges){
                if (Objects.equals(e.get("p"), p) && (dst == null || Objects.equals(e.get("dst"), dst))) return true;
            }
            return false;
        });
        scriptBindings.put("relatedIds", (Function<String, List<String>>) p -> {
            List<String> out = new ArrayList<>();
            for (Map<String,Object> e : edges){
                if (Objects.equals(e.get("p"), p)) out.add((String)e.get("dst"));
            }
            return out;
        });
        scriptBindings.put("noViolations", (Function<Void, Boolean>) v -> {
            List<Map<String,Object>> vios = (List<Map<String,Object>>) rcontext.getOrDefault("violations", List.of());
            return vios.isEmpty();
        });
    }

    @FunctionalInterface
    public interface Bi2<A,B,R> { R apply(A a, B b); }
}
