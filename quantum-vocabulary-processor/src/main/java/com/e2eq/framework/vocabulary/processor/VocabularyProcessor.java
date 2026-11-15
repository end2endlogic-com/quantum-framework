package com.e2eq.framework.vocabulary.processor;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Derives an application's functional vocabulary from its code and checks its identity pack
 * against it.
 *
 * <p>What code contributes, mirroring how {@code SecurityFilter} resolves a request's
 * {@code ResourceContext}:
 * <ul>
 *   <li>A concrete model (a subtype of {@code UnversionedBaseModel}) carrying
 *       {@code @FunctionalMapping(area, domain)}, directly or inherited: that area/domain with the
 *       persistence actions (option {@value #OPTION_PERSISTENCE_ACTIONS}, default
 *       {@value #DEFAULT_PERSISTENCE_ACTIONS}).</li>
 *   <li>Each method with {@code @FunctionalAction(value)} or a JAX-RS HTTP method annotation, own
 *       or inherited (so {@code BaseResource}'s endpoints count for every subclass). Its mapping is
 *       the {@code BaseResource} model's {@code @FunctionalMapping}, else the method's, else the
 *       class's. Its action is {@code @FunctionalAction}'s value, else the request-path keyword
 *       ({@code /list}, {@code /create}, {@code /delete}, {@code /update}) over the class and method
 *       {@code @Path}, else the HTTP verb (GET VIEW, POST CREATE, PUT/PATCH UPDATE, DELETE DELETE).</li>
 * </ul>
 * A mapping whose area the application row lists under {@code uses} is recorded as borrowed; one
 * naming a borrowed area's domain the row does not list fails the build.
 *
 * <p>Options ({@code -Akey=value}):
 * <ul>
 *   <li>{@value #OPTION_PACK}: the identity pack directory. Its application row supplies the
 *       application id, roles and {@code uses}.</li>
 *   <li>{@value #OPTION_APPLICATION}: the application id, when there is no pack or the pack holds
 *       several application rows.</li>
 *   <li>{@value #OPTION_MODE}: {@code check} (default) fails the build when the pack's vocabulary
 *       differs from code; {@code write} regenerates the pack's area and domain datasets;
 *       {@code off} only writes the manifest.</li>
 *   <li>{@value #OPTION_REGENERATE}: the regenerate command named in drift errors.</li>
 * </ul>
 * Without {@value #OPTION_PACK} or {@value #OPTION_APPLICATION} the processor does nothing.
 *
 * <p>The processor sees the sources being compiled: run it on a full compile (Maven recompiles a
 * module's sources together), not an IDE's partial one.
 */
@SupportedAnnotationTypes("*")
@SupportedOptions({
   VocabularyProcessor.OPTION_PACK,
   VocabularyProcessor.OPTION_APPLICATION,
   VocabularyProcessor.OPTION_MODE,
   VocabularyProcessor.OPTION_PERSISTENCE_ACTIONS,
   VocabularyProcessor.OPTION_REGENERATE
})
public final class VocabularyProcessor extends AbstractProcessor {

   public static final String OPTION_PACK = "quantum.vocabulary.pack";
   public static final String OPTION_APPLICATION = "quantum.vocabulary.application";
   public static final String OPTION_MODE = "quantum.vocabulary.mode";
   public static final String OPTION_PERSISTENCE_ACTIONS = "quantum.vocabulary.persistenceActions";
   public static final String OPTION_REGENERATE = "quantum.vocabulary.regenerate";

   public static final String DEFAULT_PERSISTENCE_ACTIONS = "LIST,VIEW,CREATE,UPDATE,DELETE";
   public static final String DEFAULT_REGENERATE = "mvn clean compile -Dquantum.vocabulary.mode=write";

   static final String FUNCTIONAL_MAPPING = "com.e2eq.framework.annotations.FunctionalMapping";
   static final String FUNCTIONAL_ACTION = "com.e2eq.framework.annotations.FunctionalAction";
   static final String BASE_RESOURCE = "com.e2eq.framework.rest.core.BaseResource";
   static final String BASE_MODEL = "com.e2eq.framework.model.persistent.base.UnversionedBaseModel";
   static final String HTTP_METHOD = "jakarta.ws.rs.HttpMethod";
   static final String PATH = "jakarta.ws.rs.Path";

   private record Mapping(String area, String domain) {
   }

   private record Use(String application, String area, Set<String> domainKeys) {
   }

   private record Contribution(Mapping mapping, String action, String source, Element element) {
   }

   private final List<Contribution> contributions = new ArrayList<>();
   private final Set<String> seen = new TreeSet<>();
   private boolean failed;

   @Override
   public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latestSupported();
   }

   @Override
   public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
      if (!configured()) {
         return false;
      }
      for (Element root : round.getRootElements()) {
         collect(root);
      }
      if (round.processingOver()) {
         finish();
      }
      return false;
   }

   private boolean configured() {
      return option(OPTION_PACK) != null || option(OPTION_APPLICATION) != null;
   }

   private String option(String name) {
      String value = processingEnv.getOptions().get(name);
      return value == null || value.isBlank() ? null : value.trim();
   }

   private void collect(Element element) {
      if (element instanceof TypeElement type) {
         if (seen.add(type.getQualifiedName().toString())) {
            collectType(type);
         }
         for (Element enclosed : type.getEnclosedElements()) {
            collect(enclosed);
         }
      }
   }

   private void collectType(TypeElement type) {
      if (type.getKind() != ElementKind.CLASS && type.getKind() != ElementKind.RECORD) {
         return;
      }
      if (type.getModifiers().contains(Modifier.ABSTRACT)) {
         return; // concrete subclasses collect what they inherit
      }
      Elements elements = processingEnv.getElementUtils();
      Mapping typeMapping = mapping(elements.getAllAnnotationMirrors(type));
      String typeName = type.getQualifiedName().toString();
      if (typeMapping != null && isSubtype(type, BASE_MODEL)) {
         for (String action : persistenceActions()) {
            contributions.add(new Contribution(typeMapping, action, typeName + " (model)", type));
         }
      }
      Mapping modelMapping = null;
      boolean modelWarned = false;
      if (isSubtype(type, BASE_RESOURCE)) {
         TypeElement model = resourceModel(type);
         if (model != null) {
            modelMapping = mapping(elements.getAllAnnotationMirrors(model));
            if (modelMapping == null) {
               processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                  "FUNCTIONAL_MAPPING_NOT_IN_CODE: " + typeName + " serves " + model.getQualifiedName()
                     + ", which carries no @FunctionalMapping; its bmFunctionalArea()/bmFunctionalDomain() cannot be read"
                     + " at build time, so its vocabulary is missing from " + VocabularyManifest.RESOURCE
                     + ". Annotate the model.", type);
               modelWarned = true;
            }
         }
      }
      String classPath = path(type.getAnnotationMirrors());
      List<String> unmapped = new ArrayList<>();
      for (Element member : elements.getAllMembers(type)) {
         if (member.getKind() != ElementKind.METHOD) {
            continue;
         }
         ExecutableElement method = (ExecutableElement) member;
         String declared = declaredAction(method);
         String httpVerb = httpVerb(method);
         if (declared == null && httpVerb == null) {
            continue;
         }
         Mapping mapping = modelMapping != null ? modelMapping : mapping(method.getAnnotationMirrors());
         if (mapping == null) {
            mapping = typeMapping;
         }
         String source = typeName + "#" + method.getSimpleName();
         if (mapping == null) {
            if (declared != null) {
               error("FUNCTIONAL_MAPPING_MISSING: " + source + " declares @FunctionalAction(\"" + declared
                  + "\") but neither it, its class nor its resource model carries @FunctionalMapping.", method);
            } else {
               unmapped.add(method.getSimpleName().toString());
            }
            continue;
         }
         String action = declared;
         if (action == null) {
            action = keywordAction(join(classPath, path(method.getAnnotationMirrors())));
         }
         if (action == null) {
            action = verbAction(httpVerb);
         }
         if ("list".equalsIgnoreCase(action)) {
            action = "LIST";
         }
         contributions.add(new Contribution(mapping, action, source, method));
      }
      if (!unmapped.isEmpty() && classPath != null && !modelWarned) {
         processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
            "FUNCTIONAL_MAPPING_MISSING: " + typeName + " endpoints " + new TreeSet<>(unmapped)
               + " carry no @FunctionalMapping, so they resolve to the anonymous context and are outside the vocabulary.",
            type);
      }
   }

   private void finish() {
      Messager messager = processingEnv.getMessager();
      String packOption = option(OPTION_PACK);
      IdentityPackVocabulary pack = null;
      if (packOption != null) {
         try {
            pack = IdentityPackVocabulary.read(Path.of(packOption), option(OPTION_APPLICATION));
         } catch (IOException e) {
            error("IDENTITY_PACK_UNREADABLE: " + e.getMessage(), null);
            return;
         }
      }
      String applicationId = pack != null ? pack.applicationId() : option(OPTION_APPLICATION);
      Map<String, Use> uses = new LinkedHashMap<>();
      if (pack != null) {
         pack.uses().forEach((k, v) -> uses.put(k, new Use(v.application(), v.area(), v.domainKeys())));
      }
      VocabularyManifest.Builder builder = new VocabularyManifest.Builder(applicationId);
      for (Contribution c : contributions) {
         String area = c.mapping().area();
         String domain = c.mapping().domain();
         if (area == null || domain == null || area.contains("*") || domain.contains("*")) {
            error("FUNCTIONAL_MAPPING_INVALID: " + c.source() + " maps area '" + area + "' domain '" + domain
               + "'; a mapping names one concrete area and domain.", c.element());
            continue;
         }
         if (c.action() == null || c.action().isBlank() || c.action().contains("*")) {
            error("FUNCTIONAL_ACTION_INVALID: " + c.source() + " resolves to action '" + c.action() + "'.", c.element());
            continue;
         }
         Use use = uses.get(VocabularyManifest.key(area));
         if (use == null) {
            builder.owned(area, domain, c.action(), c.source());
         } else if (use.domainKeys().contains(VocabularyManifest.key(domain))) {
            builder.borrowed(use.application(), use.area(), domain, c.action(), c.source());
         } else {
            error("FUNCTIONAL_MAPPING_UNDECLARED: " + c.source() + " maps " + area + "/" + domain + ", but area "
               + area + " is borrowed from " + use.application() + " and the application's 'uses' does not list domain "
               + domain + ".", c.element());
         }
      }
      if (pack != null) {
         pack.roles().forEach(builder::role);
      }
      if (failed) {
         return;
      }
      VocabularyManifest manifest = builder.build();
      if (manifest.areas().isEmpty() && manifest.uses().isEmpty()) {
         messager.printMessage(Diagnostic.Kind.WARNING, "FUNCTIONAL_MAPPING_NONE: no @FunctionalMapping found for "
            + applicationId + "; " + VocabularyManifest.RESOURCE + " declares no vocabulary.");
      }
      try {
         FileObject file = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", VocabularyManifest.RESOURCE);
         try (Writer writer = file.openWriter()) {
            writer.write(manifest.toJson());
         }
      } catch (IOException e) {
         error("VOCABULARY_MANIFEST_UNWRITABLE: " + e.getMessage(), null);
         return;
      }
      if (pack == null) {
         return;
      }
      String mode = option(OPTION_MODE) == null ? "check" : option(OPTION_MODE).toLowerCase(Locale.ROOT);
      switch (mode) {
         case "off" -> { }
         case "write" -> {
            try {
               List<Path> written = pack.write(manifest);
               messager.printMessage(Diagnostic.Kind.NOTE, "Regenerated " + written + " from code ("
                  + manifest.contentHash() + ").");
            } catch (IOException e) {
               error("IDENTITY_PACK_UNWRITABLE: " + e.getMessage(), null);
            }
         }
         case "check" -> {
            List<String> drift = pack.drift(manifest);
            if (!drift.isEmpty()) {
               String regenerate = option(OPTION_REGENERATE) == null ? DEFAULT_REGENERATE : option(OPTION_REGENERATE);
               error("IDENTITY_PACK_VOCABULARY_DRIFT: identity pack " + pack.packDir() + " (" + applicationId
                  + ") declares a vocabulary that differs from code (" + manifest.contentHash() + "):\n  - "
                  + String.join("\n  - ", drift) + "\nRegenerate the pack's vocabulary datasets from code with: "
                  + regenerate, null);
            }
         }
         default -> error("VOCABULARY_MODE_INVALID: " + OPTION_MODE + "=" + mode + " is not check, write or off.", null);
      }
   }

   private void error(String message, Element element) {
      failed = true;
      if (element == null) {
         processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message);
      } else {
         processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
      }
   }

   private List<String> persistenceActions() {
      String configured = option(OPTION_PERSISTENCE_ACTIONS);
      List<String> out = new ArrayList<>();
      for (String action : (configured == null ? DEFAULT_PERSISTENCE_ACTIONS : configured).split(",")) {
         if (!action.isBlank()) {
            out.add(action.trim());
         }
      }
      return out;
   }

   private boolean isSubtype(TypeElement type, String supertype) {
      TypeElement target = processingEnv.getElementUtils().getTypeElement(supertype);
      if (target == null) {
         return false;
      }
      Types types = processingEnv.getTypeUtils();
      return types.isSubtype(types.erasure(type.asType()), types.erasure(target.asType()));
   }

   /** The first generic superclass that is a BaseResource with a class as its first argument. */
   private TypeElement resourceModel(TypeElement type) {
      Types types = processingEnv.getTypeUtils();
      TypeMirror base = types.erasure(processingEnv.getElementUtils().getTypeElement(BASE_RESOURCE).asType());
      TypeMirror current = type.getSuperclass();
      while (current.getKind() == TypeKind.DECLARED) {
         DeclaredType declared = (DeclaredType) current;
         if (!declared.getTypeArguments().isEmpty() && types.isSubtype(types.erasure(declared), base)) {
            TypeMirror first = declared.getTypeArguments().get(0);
            if (first.getKind() == TypeKind.DECLARED) {
               return (TypeElement) ((DeclaredType) first).asElement();
            }
         }
         current = ((TypeElement) declared.asElement()).getSuperclass();
      }
      return null;
   }

   private Mapping mapping(List<? extends AnnotationMirror> mirrors) {
      AnnotationMirror mirror = find(mirrors, FUNCTIONAL_MAPPING);
      if (mirror == null) {
         return null;
      }
      return new Mapping(stringValue(mirror, "area"), stringValue(mirror, "domain"));
   }

   private String declaredAction(ExecutableElement method) {
      AnnotationMirror mirror = find(method.getAnnotationMirrors(), FUNCTIONAL_ACTION);
      return mirror == null ? null : stringValue(mirror, "value");
   }

   /** The HTTP verb of a JAX-RS resource method: an annotation itself annotated {@code @HttpMethod}. */
   private String httpVerb(ExecutableElement method) {
      for (AnnotationMirror mirror : method.getAnnotationMirrors()) {
         AnnotationMirror meta = find(mirror.getAnnotationType().asElement().getAnnotationMirrors(), HTTP_METHOD);
         if (meta != null) {
            return stringValue(meta, "value");
         }
      }
      return null;
   }

   private String path(List<? extends AnnotationMirror> mirrors) {
      AnnotationMirror mirror = find(mirrors, PATH);
      return mirror == null ? null : stringValue(mirror, "value");
   }

   private static String join(String classPath, String methodPath) {
      StringBuilder out = new StringBuilder();
      for (String part : new String[]{classPath, methodPath}) {
         if (part == null || part.isBlank()) {
            continue;
         }
         String trimmed = part.trim();
         out.append(trimmed.startsWith("/") ? "" : "/").append(trimmed);
      }
      return out.toString();
   }

   /** SecurityFilter.inferActionFromUrlKeywords over the resource path template. */
   static String keywordAction(String path) {
      String lower = path.toLowerCase(Locale.ROOT);
      if (lower.contains("/list")) {
         return "LIST";
      } else if (lower.contains("/create")) {
         return "CREATE";
      } else if (lower.contains("/delete")) {
         return "DELETE";
      } else if (lower.contains("/update")) {
         return "UPDATE";
      }
      return null;
   }

   /** SecurityFilter.inferActionFromHttpMethod. */
   static String verbAction(String verb) {
      return switch (verb.toUpperCase(Locale.ROOT)) {
         case "GET" -> "VIEW";
         case "POST" -> "CREATE";
         case "PUT", "PATCH" -> "UPDATE";
         case "DELETE" -> "DELETE";
         default -> verb;
      };
   }

   private static AnnotationMirror find(List<? extends AnnotationMirror> mirrors, String qualifiedName) {
      for (AnnotationMirror mirror : mirrors) {
         Element element = mirror.getAnnotationType().asElement();
         if (element instanceof TypeElement t && t.getQualifiedName().contentEquals(qualifiedName)) {
            return mirror;
         }
      }
      return null;
   }

   private String stringValue(AnnotationMirror mirror, String name) {
      for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
         : processingEnv.getElementUtils().getElementValuesWithDefaults(mirror).entrySet()) {
         if (entry.getKey().getSimpleName().contentEquals(name)) {
            Object value = entry.getValue().getValue();
            return value instanceof String s && !s.isBlank() ? s.trim() : null;
         }
      }
      return null;
   }
}
