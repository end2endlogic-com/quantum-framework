package com.e2eq.ontology.policy;

import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

@SuppressWarnings("unchecked")
public final class ScriptHelpers {
    private ScriptHelpers(){}

    public static void install(Map<String,Object> scriptBindings){
        Map<String,Object> rcontext = (Map<String,Object>) scriptBindings.getOrDefault("rcontext", new HashMap<>());
        List<?> edges = (List<?>) rcontext.getOrDefault("edges", List.of());

        scriptBindings.put("isA", (Function<String, Boolean>) t -> {
            List<String> types = (List<String>) rcontext.getOrDefault("types", List.of());
            return types != null && types.contains(t);
        });
        scriptBindings.put("hasLabel", (Function<String, Boolean>) l -> {
            List<String> labels = (List<String>) rcontext.getOrDefault("labels", List.of());
            return labels != null && labels.contains(l);
        });
        scriptBindings.put("hasEdge", (EdgeHelper<String, String, Boolean>) (p, dst, filter) -> {
            for (Object raw : edges){
                Map<String,Object> e = toEdgeMap(raw);
                if (Objects.equals(e.get("p"), p) && (dst == null || Objects.equals(e.get("dst"), dst))) {
                    if (matchesEdgeFilter(e, filter)) return true;
                }
            }
            return false;
        });
        scriptBindings.put("hasIncomingEdge", (EdgeHelper<String, String, Boolean>) (p, src, filter) -> {
            for (Object raw : edges){
                Map<String,Object> e = toEdgeMap(raw);
                if (Objects.equals(e.get("p"), p) && (src == null || Objects.equals(e.get("src"), src))) {
                    if (matchesEdgeFilter(e, filter)) return true;
                }
            }
            return false;
        });
        scriptBindings.put("hasAnyEdge", (EdgeCollectionHelper<String, Collection<String>, Boolean>) (p, dsts, filter) -> {
            if (p == null || dsts == null || dsts.isEmpty()) return false;
            for (Object raw : edges){
                Map<String,Object> e = toEdgeMap(raw);
                if (!Objects.equals(e.get("p"), p)) continue;
                Object d = e.get("dst");
                if (d instanceof String ds && dsts.contains(ds)) {
                    if (matchesEdgeFilter(e, filter)) return true;
                }
            }
            return false;
        });

        // hasAllEdges(predicate, [dst1, dst2, ...], filter) -> true only if there is a matching edge for every dst
        scriptBindings.put("hasAllEdges", (EdgeCollectionHelper<String, Collection<String>, Boolean>) (p, dsts, filter) -> {
            if (p == null || dsts == null || dsts.isEmpty()) return false;
            Set<String> present = new HashSet<>();
            for (Object raw : edges){
                Map<String,Object> e = toEdgeMap(raw);
                if (Objects.equals(e.get("p"), p) && matchesEdgeFilter(e, filter)) {
                    Object d = e.get("dst");
                    if (d instanceof String ds) present.add(ds);
                }
            }
            for (String required : dsts) {
                if (!present.contains(required)) return false;
            }
            return true;
        });

        scriptBindings.put("relatedIds", (RelatedIdsHelper<String, List<String>>) (p, filter) -> {
            List<String> out = new ArrayList<>();
            for (Object raw : edges){
                Map<String,Object> e = toEdgeMap(raw);
                if (Objects.equals(e.get("p"), p) && matchesEdgeFilter(e, filter)) {
                    out.add((String) e.get("dst"));
                }
            }
            return out;
        });
        scriptBindings.put("noViolations", (Function<Void, Boolean>) v -> {
            List<Map<String,Object>> vios = (List<Map<String,Object>>) rcontext.getOrDefault("violations", List.of());
            return vios == null || vios.isEmpty();
        });
    }

    public static boolean matchesEdgeFilter(Map<String, Object> edge, Object filter) {
        if (filter == null) {
            return true;
        }
        if (edge == null) {
            return false;
        }
        Map<String, Object> view = createEdgeView(edge);

        if (filter instanceof Predicate pred) {
            try {
                return pred.test(view);
            } catch (Throwable t) {
                return false;
            }
        }
        if (filter instanceof Function fn) {
            try {
                Object res = fn.apply(view);
                return isTruthy(res);
            } catch (Throwable t) {
                return false;
            }
        }
        if (filter instanceof Map<?, ?> filterMap) {
            for (Map.Entry<?, ?> entry : filterMap.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object expected = entry.getValue();
                Object actual = view.get(key);

                if (!matchesValue(actual, expected)) {
                    return false;
                }
            }
            return true;
        }
        // Fallback for reflective callable (e.g. GraalVM Value with execute())
        try {
            Method executeMethod = null;
            for (Method m : filter.getClass().getMethods()) {
                if ("execute".equals(m.getName()) && m.getParameterCount() == 1) {
                    executeMethod = m;
                    break;
                }
            }
            if (executeMethod != null) {
                Object res = executeMethod.invoke(filter, view);
                return isTruthy(res);
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    public static Map<String, Object> createEdgeView(Map<String, Object> edge) {
        if (edge == null) return Collections.emptyMap();
        Object propsObj = edge.get("props");
        final Map<String, Object> propsMap;
        if (propsObj instanceof Map<?, ?> m) {
            propsMap = (Map<String, Object>) m;
        } else {
            propsMap = Collections.emptyMap();
        }

        return new AbstractMap<String, Object>() {
            @Override
            public Object get(Object key) {
                if (edge.containsKey(key)) {
                    return edge.get(key);
                }
                if (propsMap.containsKey(key)) {
                    return propsMap.get(key);
                }
                if (key instanceof String s && s.startsWith("props.")) {
                    return propsMap.get(s.substring(6));
                }
                return null;
            }

            @Override
            public boolean containsKey(Object key) {
                return edge.containsKey(key)
                        || propsMap.containsKey(key)
                        || (key instanceof String s && s.startsWith("props.") && propsMap.containsKey(s.substring(6)));
            }

            @Override
            public Set<Entry<String, Object>> entrySet() {
                Map<String, Object> merged = new LinkedHashMap<>(edge);
                for (Entry<String, Object> entry : propsMap.entrySet()) {
                    merged.putIfAbsent(entry.getKey(), entry.getValue());
                }
                return merged.entrySet();
            }
        };
    }

    private static Map<String, Object> toEdgeMap(Object obj) {
        if (obj instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        if (obj == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> map = new HashMap<>();
        try {
            for (Method m : obj.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && m.getName().startsWith("get") && !m.getName().equals("getClass")) {
                    String name = Character.toLowerCase(m.getName().charAt(3)) + m.getName().substring(4);
                    map.put(name, m.invoke(obj));
                } else if (m.getParameterCount() == 0 && m.getName().startsWith("is")) {
                    String name = Character.toLowerCase(m.getName().charAt(2)) + m.getName().substring(3);
                    map.put(name, m.invoke(obj));
                }
            }
        } catch (Throwable ignored) {
        }
        return map;
    }

    private static boolean isTruthy(Object res) {
        if (res == null) return false;
        if (res instanceof Boolean b) return b;
        if (res instanceof Number n) return n.doubleValue() != 0;
        if (res instanceof String s) return !s.isEmpty() && !s.equalsIgnoreCase("false");
        try {
            Method asBoolean = res.getClass().getMethod("asBoolean");
            return (boolean) asBoolean.invoke(res);
        } catch (Throwable ignored) {
        }
        return true;
    }

    private static boolean matchesValue(Object actual, Object expected) {
        if (expected == null) {
            return actual == null;
        }
        if (actual == null) {
            return false;
        }
        if (expected instanceof Predicate pred) {
            try {
                return pred.test(actual);
            } catch (Throwable t) {
                return false;
            }
        }
        if (expected instanceof Function fn) {
            try {
                return isTruthy(fn.apply(actual));
            } catch (Throwable t) {
                return false;
            }
        }
        if (expected instanceof Collection<?> expectedCol) {
            if (actual instanceof Collection<?> actualCol) {
                return !Collections.disjoint(actualCol, expectedCol);
            }
            return expectedCol.contains(actual);
        }
        if (expected instanceof Number expNum && actual instanceof Number actNum) {
            return Double.compare(actNum.doubleValue(), expNum.doubleValue()) == 0;
        }
        return Objects.equals(actual, expected) || String.valueOf(actual).equals(String.valueOf(expected));
    }

    @FunctionalInterface
    public interface Bi2<A,B,R> { R apply(A a, B b); }

    @FunctionalInterface
    public interface Tri3<A,B,C,R> {
        R apply(A a, B b, C c);
        default R apply(A a, B b) {
            return apply(a, b, null);
        }
    }

    public interface EdgeHelper<A,B,R> extends Bi2<A,B,R>, Tri3<A,B,Object,R> {
        @Override
        default R apply(A a, B b) {
            return apply(a, b, null);
        }
    }

    public interface EdgeCollectionHelper<A,B,R> extends Bi2<A,B,R>, Tri3<A,B,Object,R> {
        @Override
        default R apply(A a, B b) {
            return apply(a, b, null);
        }
    }

    public interface RelatedIdsHelper<A,R> extends Function<A,R>, Bi2<A,Object,R> {
        @Override
        default R apply(A a) {
            return apply(a, null);
        }
    }
}
