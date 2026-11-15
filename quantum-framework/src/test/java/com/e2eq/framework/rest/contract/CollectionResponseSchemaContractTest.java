package com.e2eq.framework.rest.contract;

import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.core.Response;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.WildcardType;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.CompletionStage;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Contract guard: a JAX-RS resource method whose 2xx body is a collection must declare
 * an array schema in its {@code @APIResponse}. A single-object schema on a list endpoint
 * makes every generated SDK (which validates responses) reject the real payload.
 *
 * <p>The body is known to be a collection when either
 * <ul>
 *   <li>the method's declared return type is a {@link Collection}, an object array, or a
 *       {@code Uni}/{@link CompletionStage} of one; or</li>
 *   <li>the method returns {@link Response} and its bytecode passes a collection-typed value
 *       (a call result, local variable or field) to {@code Response.ok(..)},
 *       {@code Response.status(..).entity(..)} or {@code ResponseBuilder.entity(..)}.</li>
 * </ul>
 * A schema counts as an array when it sets {@code type = SchemaType.ARRAY}, or its
 * {@code implementation} is an array class or a {@link Collection} type.
 *
 * <p>Every class under {@code com.e2eq.framework} on this module's test classpath is scanned;
 * this module depends on the framework's REST modules, so their resources are covered.
 */
class CollectionResponseSchemaContractTest {

    private static final String ROOT_PACKAGE = "com.e2eq.framework";

    @Test
    void collectionReturningResourceMethodsDeclareArraySchemas() throws Exception {
        List<Class<?>> classes = loadClasses(ROOT_PACKAGE);
        Scan scan = scan(classes);

        // The scan must actually see the framework's resources, or the guard proves nothing.
        assertTrue(scan.resourceMethods > 100,
            "expected to scan the framework's JAX-RS resource methods, saw " + scan.resourceMethods);
        assertTrue(scan.collectionMethods > 5,
            "expected to detect collection-returning resource methods, saw " + scan.collectionMethods);

        if (!scan.violations.isEmpty()) {
            fail("Resource methods return a collection but declare a single-object 2xx schema. "
                + "Add type = SchemaType.ARRAY to the @Schema:\n  "
                + String.join("\n  ", scan.violations));
        }
    }

    @Test
    void guardFlagsSingleObjectSchemasOnCollectionResponses() throws Exception {
        Scan scan = scan(List.of(Fixtures.class));
        assertEquals(new TreeSet<>(List.of(
                "Fixtures#badArray 200",
                "Fixtures#badBuilderEntity 200",
                "Fixtures#badConstructed 200",
                "Fixtures#badLocal 200",
                "Fixtures#badResponseOk 200",
                "Fixtures#badTypeVariable 200",
                "Fixtures#badTypedList 200",
                "Fixtures#badUni 201",
                "Fixtures#badWithMediaType 200")),
            new TreeSet<>(scan.violations.stream().map(v -> v.substring(v.lastIndexOf('$') + 1)).toList()));
    }

    // ---------------------------------------------------------------- scanning

    private record Scan(int resourceMethods, int collectionMethods, List<String> violations) {}

    private static Scan scan(List<Class<?>> classes) throws IOException {
        int resourceMethods = 0;
        int collectionMethods = 0;
        List<String> violations = new ArrayList<>();
        for (Class<?> type : classes) {
            Method[] methods;
            try {
                methods = type.getDeclaredMethods();
            } catch (LinkageError e) {
                continue; // optional dependency absent from this classpath
            }
            Map<String, MethodNode> bytecode = null;
            for (Method method : methods) {
                if (method.isSynthetic() || method.isBridge() || !isResourceMethod(method)) {
                    continue;
                }
                resourceMethods++;
                boolean collection = returnsCollectionType(method);
                if (!collection && Response.class.equals(method.getReturnType())) {
                    if (bytecode == null) {
                        bytecode = readMethods(type);
                    }
                    MethodNode node = bytecode.get(method.getName() + Type.getMethodDescriptor(method));
                    collection = node != null && passesCollectionToResponse(node);
                }
                if (!collection) {
                    continue;
                }
                collectionMethods++;
                for (APIResponse response : successResponses(method)) {
                    for (Content content : response.content()) {
                        Schema schema = content.schema();
                        if (declaresSchema(schema) && !isArraySchema(schema)) {
                            violations.add(type.getName() + "#" + method.getName() + " " + response.responseCode());
                            break;
                        }
                    }
                }
            }
        }
        Collections.sort(violations);
        return new Scan(resourceMethods, collectionMethods, violations);
    }

    private static boolean isResourceMethod(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            if (annotation.annotationType().isAnnotationPresent(HttpMethod.class)) {
                return true;
            }
        }
        return false;
    }

    private static List<APIResponse> successResponses(Method method) {
        List<APIResponse> all = new ArrayList<>(Arrays.asList(method.getAnnotationsByType(APIResponse.class)));
        APIResponses container = method.getAnnotation(APIResponses.class);
        if (container != null) {
            all.addAll(Arrays.asList(container.value()));
        }
        return all.stream().filter(r -> r.responseCode().startsWith("2")).toList();
    }

    private static boolean declaresSchema(Schema schema) {
        return schema.implementation() != Void.class || schema.type() != SchemaType.DEFAULT || !schema.ref().isEmpty();
    }

    private static boolean isArraySchema(Schema schema) {
        Class<?> impl = schema.implementation();
        return schema.type() == SchemaType.ARRAY || impl.isArray() || Collection.class.isAssignableFrom(impl);
    }

    // ---------------------------------------------------------------- declared return types

    private static boolean returnsCollectionType(Method method) {
        return isCollection(method.getGenericReturnType());
    }

    private static boolean isCollection(java.lang.reflect.Type type) {
        if (type instanceof Class<?> c) {
            return isCollectionClass(c);
        }
        if (type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> raw) {
            if (Collection.class.isAssignableFrom(raw)) {
                return true;
            }
            if (isAsyncWrapper(raw) && p.getActualTypeArguments().length == 1) {
                return isCollection(p.getActualTypeArguments()[0]);
            }
            return false;
        }
        if (type instanceof java.lang.reflect.GenericArrayType) {
            return true;
        }
        if (type instanceof WildcardType w && w.getUpperBounds().length == 1) {
            return isCollection(w.getUpperBounds()[0]);
        }
        if (type instanceof java.lang.reflect.TypeVariable<?> v) {
            return Arrays.stream(v.getBounds()).anyMatch(CollectionResponseSchemaContractTest::isCollection);
        }
        return false;
    }

    private static boolean isAsyncWrapper(Class<?> raw) {
        return CompletionStage.class.isAssignableFrom(raw) || "io.smallrye.mutiny.Uni".equals(raw.getName());
    }

    private static boolean isCollectionClass(Class<?> c) {
        return Collection.class.isAssignableFrom(c) || (c.isArray() && !c.getComponentType().isPrimitive());
    }

    // ---------------------------------------------------------------- Response bodies

    private static final String RESPONSE = "jakarta/ws/rs/core/Response";
    private static final String BUILDER = "jakarta/ws/rs/core/Response$ResponseBuilder";
    private static final String ENTITY_DESC_PREFIX = "(Ljava/lang/Object;";

    private static Map<String, MethodNode> readMethods(Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return Map.of();
            }
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES);
            Map<String, MethodNode> byKey = new java.util.HashMap<>();
            for (MethodNode m : node.methods) {
                byKey.put(m.name + m.desc, m);
            }
            return byKey;
        }
    }

    /** True when a collection-typed value is the body handed to Response.ok / entity. */
    private static boolean passesCollectionToResponse(MethodNode method) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode call) || !isEntitySink(call)) {
                continue;
            }
            AbstractInsnNode arg = previousReal(insn);
            // ok(entity, mediaType|String|Variant): step over a second argument pushed by one instruction.
            if (Type.getArgumentTypes(call.desc).length == 2 && arg != null && isSinglePush(arg)) {
                arg = previousReal(arg);
            }
            while (arg != null && arg.getOpcode() == Opcodes.CHECKCAST) {
                arg = previousReal(arg);
            }
            if (arg != null && producesCollection(arg, method)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEntitySink(MethodInsnNode call) {
        if (!call.desc.startsWith(ENTITY_DESC_PREFIX)) {
            return false;
        }
        return (RESPONSE.equals(call.owner) && "ok".equals(call.name))
            || (BUILDER.equals(call.owner) && "entity".equals(call.name));
    }

    private static boolean isSinglePush(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        return op == Opcodes.GETSTATIC || op == Opcodes.LDC || op == Opcodes.ALOAD || op == Opcodes.ACONST_NULL;
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
        AbstractInsnNode p = insn.getPrevious();
        while (p != null && p.getOpcode() < 0) { // labels, line numbers, frames
            p = p.getPrevious();
        }
        return p;
    }

    private static boolean producesCollection(AbstractInsnNode insn, MethodNode method) {
        if (insn instanceof MethodInsnNode call) {
            if ("<init>".equals(call.name)) { // new ArrayList<>(..): the constructed type is the owner
                return isCollectionDescriptor(Type.getObjectType(call.owner));
            }
            return isCollectionDescriptor(Type.getReturnType(call.desc));
        }
        if (insn instanceof FieldInsnNode field) {
            return isCollectionDescriptor(Type.getType(field.desc));
        }
        if (insn instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && method.localVariables != null) {
            for (LocalVariableNode local : method.localVariables) {
                if (local.index == load.var
                    && method.instructions.indexOf(local.start) <= method.instructions.indexOf(insn)
                    && method.instructions.indexOf(insn) <= method.instructions.indexOf(local.end)) {
                    return isCollectionDescriptor(Type.getType(local.desc));
                }
            }
        }
        return false;
    }

    private static boolean isCollectionDescriptor(Type type) {
        if (type.getSort() == Type.ARRAY) {
            return type.getElementType().getSort() == Type.OBJECT;
        }
        if (type.getSort() != Type.OBJECT) {
            return false;
        }
        try {
            Class<?> c = Class.forName(type.getClassName(), false,
                CollectionResponseSchemaContractTest.class.getClassLoader());
            return Collection.class.isAssignableFrom(c);
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- classpath

    private static List<Class<?>> loadClasses(String rootPackage) throws IOException {
        ClassLoader loader = CollectionResponseSchemaContractTest.class.getClassLoader();
        String path = rootPackage.replace('.', '/');
        TreeSet<String> names = new TreeSet<>();
        Enumeration<URL> roots = loader.getResources(path);
        while (roots.hasMoreElements()) {
            URL url = roots.nextElement();
            if (url.getPath().contains("/test-classes/")) {
                continue; // production resources only; test fixtures may be deliberately wrong
            }
            if ("file".equals(url.getProtocol())) {
                File dir = new File(URLDecoder.decode(url.getPath(), StandardCharsets.UTF_8));
                collect(dir, rootPackage, names);
            } else if ("jar".equals(url.getProtocol())) {
                JarURLConnection connection = (JarURLConnection) url.openConnection();
                connection.setUseCaches(false);
                try (JarFile jar = connection.getJarFile()) {
                    for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
                        String entry = e.nextElement().getName();
                        if (entry.startsWith(path + "/") && entry.endsWith(".class")) {
                            names.add(entry.substring(0, entry.length() - 6).replace('/', '.'));
                        }
                    }
                }
            }
        }
        String self = CollectionResponseSchemaContractTest.class.getName();
        List<Class<?>> classes = new ArrayList<>();
        for (String name : names) {
            if (name.startsWith(self) || name.endsWith("module-info") || name.endsWith("package-info")) {
                continue;
            }
            try {
                classes.add(Class.forName(name, false, loader));
            } catch (ClassNotFoundException | LinkageError e) {
                // optional dependency absent from this classpath
            }
        }
        return classes;
    }

    private static void collect(File dir, String pkg, Collection<String> names) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                collect(f, pkg + "." + f.getName(), names);
            } else if (f.getName().endsWith(".class")) {
                names.add(pkg + "." + f.getName().substring(0, f.getName().length() - 6));
            }
        }
    }

    // ---------------------------------------------------------------- fixtures

    @SuppressWarnings("unused")
    static class Fixtures {
        static class Item {}

        private final List<Item> items = List.of();

        @jakarta.ws.rs.GET
        @APIResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = Item.class)))
        public List<Item> badTypedList() { return items; }

        @jakarta.ws.rs.GET
        @APIResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = Item.class)))
        public Item[] badArray() { return new Item[0]; }

        @jakarta.ws.rs.POST
        @APIResponses({
            @APIResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = Item.class))),
            @APIResponse(responseCode = "400", content = @Content(schema = @Schema(implementation = Item.class)))
        })
        public CompletionStage<List<Item>> badUni() { return null; }

        @jakarta.ws.rs.GET
        @APIResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = Item.class)))
        public Response badResponseOk() { return Response.ok(listItems()).build(); }

        @jakarta.ws.rs.GET
        @APIResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = Item.class)))
        public Response badBuilderEntity() { return Response.status(200).entity(items).build(); }

        @jakarta.ws.rs.GET
        @APIResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = Item.class)))
        public Response badLocal() {
            List<Item> found = listItems();
            return Response.ok(found).build();
        }

        @jakarta.ws.rs.GET
        @APIResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = Item.class)))
        public Response badConstructed() { return Response.ok(new ArrayList<>(listItems())).build(); }

        @jakarta.ws.rs.GET
        @APIResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = Item.class)))
        public Response badWithMediaType() { return Response.ok(listItems(), jakarta.ws.rs.core.MediaType.APPLICATION_JSON_TYPE).build(); }

        @jakarta.ws.rs.GET
        @APIResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = Item.class)))
        public <C extends List<Item>> C badTypeVariable() { return null; }

        @jakarta.ws.rs.GET
        @APIResponse(responseCode = "200", content = @Content(schema = @Schema(type = SchemaType.ARRAY, implementation = Item.class)))
        public Response goodArrayType() { return Response.ok(listItems()).build(); }

        @jakarta.ws.rs.GET
        @APIResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = Item[].class)))
        public List<Item> goodArrayImplementation() { return items; }

        @jakarta.ws.rs.GET
        @APIResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = Item.class)))
        public Response goodSingle() { return Response.ok(Optional.of(new Item()).get()).build(); }

        @jakarta.ws.rs.GET
        @APIResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = Item.class)))
        public Response goodBytes() { return Response.ok(new byte[0]).build(); }

        @jakarta.ws.rs.GET
        public List<Item> goodNoSchema() { return items; }

        private List<Item> listItems() { return items; }
    }
}
