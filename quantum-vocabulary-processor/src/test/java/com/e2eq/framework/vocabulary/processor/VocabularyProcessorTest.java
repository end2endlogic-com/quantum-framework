package com.e2eq.framework.vocabulary.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compiles small applications against the real {@code @FunctionalMapping}, {@code @FunctionalAction}
 * and {@code BaseResource} with the processor, and checks the manifest and the pack check.
 */
class VocabularyProcessorTest {

   private static final ObjectMapper JSON = new ObjectMapper();

   @TempDir
   Path tmp;

   /** A model, its BaseResource with an extra action, a non-model resource, and a borrowed domain. */
   static Map<String, String> app() {
      Map<String, String> sources = new LinkedHashMap<>();
      sources.put("demo.Plan", """
         package demo;
         import com.e2eq.framework.annotations.FunctionalMapping;
         import com.e2eq.framework.model.persistent.base.BaseModel;
         @FunctionalMapping(area = "PLANNING", domain = "PLAN")
         public class Plan extends BaseModel { }
         """);
      sources.put("demo.PlanRepo", """
         package demo;
         import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
         public class PlanRepo extends MorphiaRepo<Plan> { }
         """);
      sources.put("demo.PlanResource", """
         package demo;
         import com.e2eq.framework.annotations.FunctionalAction;
         import com.e2eq.framework.rest.core.BaseResource;
         import jakarta.ws.rs.POST;
         import jakarta.ws.rs.Path;
         @Path("plans")
         public class PlanResource extends BaseResource<Plan, PlanRepo> {
            PlanResource(PlanRepo repo) { super(repo); }
            @POST @Path("{id}/approve") @FunctionalAction("APPROVE")
            public void approve() { }
         }
         """);
      sources.put("demo.DependencyResource", """
         package demo;
         import com.e2eq.framework.annotations.FunctionalMapping;
         import jakarta.ws.rs.GET;
         import jakarta.ws.rs.Path;
         @Path("platform/dependencies")
         @FunctionalMapping(area = "PLATFORM", domain = "DEPENDENCY")
         public class DependencyResource {
            @GET @Path("list") public String all() { return ""; }
            @GET @Path("{name}") public String one() { return ""; }
         }
         """);
      sources.put("demo.MenuResource", """
         package demo;
         import com.e2eq.framework.annotations.FunctionalMapping;
         import jakarta.ws.rs.GET;
         import jakarta.ws.rs.Path;
         @Path("menu")
         public class MenuResource {
            @GET @FunctionalMapping(area = "SYSTEM", domain = "MENU_ITEM") public String menu() { return ""; }
         }
         """);
      return sources;
   }

   private Path pack(String domainsJsonl) throws IOException {
      Path dir = Files.createDirectories(tmp.resolve("pack"));
      Files.createDirectories(dir.resolve("datasets"));
      Files.writeString(dir.resolve("manifest.yaml"), """
         seedPack: acme-planner-identity
         version: 1.0.0
         datasets:
           - collection: application
             naturalKey: [refName]
             file: datasets/applications.jsonl
           - collection: functionalArea
             naturalKey: [refName]
             file: datasets/functionalAreas.jsonl
           - collection: functionalDomain
             naturalKey: [applicationId, area, refName]
             file: datasets/functionalDomains.jsonl
         """);
      Files.writeString(dir.resolve("datasets/applications.jsonl"),
         "{\"refName\":\"acme-planner\",\"roles\":[{\"name\":\"planner\"},{\"name\":\"approver\"}],"
            + "\"uses\":[{\"application\":\"quantum-system\",\"area\":\"SYSTEM\",\"domains\":[\"MENU_ITEM\"]}]}\n");
      Files.writeString(dir.resolve("datasets/functionalAreas.jsonl"),
         "{\"refName\":\"PLANNING\",\"applicationId\":\"acme-planner\"}\n{\"refName\":\"PLATFORM\",\"applicationId\":\"acme-planner\"}\n");
      Files.writeString(dir.resolve("datasets/functionalDomains.jsonl"), domainsJsonl);
      return dir;
   }

   static String domain(String area, String name, String... actions) {
      String list = java.util.Arrays.stream(actions).map(a -> "{\"refName\":\"" + a + "\"}").collect(Collectors.joining(","));
      return "{\"refName\":\"" + name + "\",\"applicationId\":\"acme-planner\",\"area\":\"" + area
         + "\",\"dataDomain\":{\"tenantId\":\"t1\"},\"functionalActions\":[" + list + "]}\n";
   }

   static final String MATCHING = domain("PLANNING", "PLAN", "APPROVE", "CREATE", "DELETE", "LIST", "UPDATE", "VIEW")
      + domain("PLATFORM", "DEPENDENCY", "LIST", "VIEW");

   record Result(boolean success, List<String> errors, List<String> warnings, Path output) {
      String manifest() throws IOException {
         return Files.readString(output.resolve(VocabularyManifest.RESOURCE));
      }

      JsonNode json() throws IOException {
         return JSON.readTree(manifest());
      }
   }

   private Result compile(Map<String, String> sources, Map<String, String> options) throws IOException {
      Path out = Files.createTempDirectory(tmp, "classes");
      JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
      DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
      List<JavaFileObject> units = new ArrayList<>();
      sources.forEach((name, code) -> units.add(new SimpleJavaFileObject(
         URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
         @Override
         public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
         }
      }));
      List<String> args = new ArrayList<>(List.of("-d", out.toString(), "-proc:only",
         "-classpath", System.getProperty("java.class.path")));
      options.forEach((k, v) -> args.add("-A" + k + "=" + v));
      try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
         JavaCompiler.CompilationTask task = compiler.getTask(null, files, diagnostics, args, null, units);
         task.setProcessors(List.of(new VocabularyProcessor()));
         boolean ok = task.call();
         List<String> errors = new ArrayList<>();
         List<String> warnings = new ArrayList<>();
         for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            String message = d.getMessage(null);
            if (d.getKind() == Diagnostic.Kind.ERROR) {
               errors.add(message);
            } else if (message.contains("FUNCTIONAL_")) {
               warnings.add(message);
            }
         }
         return new Result(ok, errors, warnings, out);
      }
   }

   private static List<String> actions(JsonNode manifest, String area, String domain) {
      for (JsonNode a : manifest.get("areas")) {
         if (a.get("refName").asText().equals(area)) {
            for (JsonNode d : a.get("domains")) {
               if (d.get("refName").asText().equals(domain)) {
                  List<String> out = new ArrayList<>();
                  d.get("actions").forEach(x -> out.add(x.asText()));
                  return out;
               }
            }
         }
      }
      return null;
   }

   @Test
   void manifestDerivesVocabularyFromCode() throws IOException {
      Result result = compile(app(), Map.of(VocabularyProcessor.OPTION_PACK, pack(MATCHING).toString()));
      assertTrue(result.success(), () -> String.join("\n", result.errors()));
      JsonNode manifest = result.json();
      assertEquals(VocabularyManifest.SCHEMA, manifest.get("schema").asText());
      assertEquals("acme-planner", manifest.get("applicationId").asText());
      assertTrue(manifest.get("contentHash").asText().startsWith("sha256:"));
      // The model's persistence actions, BaseResource's inherited endpoints, and the declared APPROVE.
      assertEquals(List.of("APPROVE", "CREATE", "DELETE", "LIST", "UPDATE", "VIEW"), actions(manifest, "PLANNING", "PLAN"));
      // A non-model resource: /list keyword, then the GET verb.
      assertEquals(List.of("LIST", "VIEW"), actions(manifest, "PLATFORM", "DEPENDENCY"));
      // SYSTEM is borrowed through the application's uses, so it is not an owned area.
      assertEquals(2, manifest.get("areas").size());
      JsonNode use = manifest.get("uses").get(0);
      assertEquals("quantum-system", use.get("application").asText());
      assertEquals("SYSTEM", use.get("area").asText());
      assertEquals("MENU_ITEM", use.get("domains").get(0).get("refName").asText());
      assertEquals("VIEW", use.get("domains").get(0).get("actions").get(0).asText());
      assertEquals("[\"approver\",\"planner\"]", manifest.get("roles").toString());
      assertTrue(manifest.get("sources").get("PLANNING/PLAN").toString().contains("demo.PlanResource#approve"));
   }

   @Test
   void manifestIsDeterministic() throws IOException {
      Path pack = pack(MATCHING);
      Map<String, String> reversed = new LinkedHashMap<>();
      List<Map.Entry<String, String>> entries = new ArrayList<>(app().entrySet());
      java.util.Collections.reverse(entries);
      entries.forEach(e -> reversed.put(e.getKey(), e.getValue()));
      String first = compile(app(), Map.of(VocabularyProcessor.OPTION_PACK, pack.toString())).manifest();
      String second = compile(reversed, Map.of(VocabularyProcessor.OPTION_PACK, pack.toString())).manifest();
      assertEquals(first, second);
   }

   @Test
   void hashCoversVocabularyNotWhereItIsDeclared() throws IOException {
      Map<String, String> moved = app();
      // The same DEPENDENCY vocabulary declared per method instead of on the class.
      moved.put("demo.DependencyResource", """
         package demo;
         import com.e2eq.framework.annotations.FunctionalAction;
         import com.e2eq.framework.annotations.FunctionalMapping;
         import jakarta.ws.rs.GET;
         import jakarta.ws.rs.Path;
         @Path("deps")
         public class DependencyResource {
            @GET @FunctionalMapping(area = "PLATFORM", domain = "DEPENDENCY") @FunctionalAction("LIST")
            public String all() { return ""; }
            @GET @Path("{name}") @FunctionalMapping(area = "PLATFORM", domain = "DEPENDENCY")
            public String one() { return ""; }
         }
         """);
      Map<String, String> options = Map.of(VocabularyProcessor.OPTION_APPLICATION, "acme-planner");
      String a = compile(app(), options).json().get("contentHash").asText();
      String b = compile(moved, options).json().get("contentHash").asText();
      assertEquals(a, b);
      Map<String, String> changed = app();
      changed.put("demo.Extra", """
         package demo;
         import com.e2eq.framework.annotations.FunctionalAction;
         import com.e2eq.framework.annotations.FunctionalMapping;
         @FunctionalMapping(area = "PLANNING", domain = "PLAN")
         public class Extra { @FunctionalAction("ARCHIVE") public void archive() { } }
         """);
      assertNotEquals(a, compile(changed, options).json().get("contentHash").asText());
   }

   @Test
   void matchingPackPassesTheCheck() throws IOException {
      Result result = compile(app(), Map.of(VocabularyProcessor.OPTION_PACK, pack(MATCHING).toString()));
      assertTrue(result.success(), () -> String.join("\n", result.errors()));
   }

   @Test
   void missingActionFailsTheCheckAndNamesTheRegenerateCommand() throws IOException {
      String packDomains = domain("PLANNING", "PLAN", "CREATE", "DELETE", "LIST", "UPDATE", "VIEW")
         + domain("PLATFORM", "DEPENDENCY", "LIST", "VIEW");
      Result result = compile(app(), Map.of(VocabularyProcessor.OPTION_PACK, pack(packDomains).toString()));
      assertFalse(result.success());
      String error = String.join("\n", result.errors());
      assertTrue(error.contains("IDENTITY_PACK_VOCABULARY_DRIFT"), error);
      assertTrue(error.contains("missing action PLANNING/PLAN.APPROVE"), error);
      assertTrue(error.contains(VocabularyProcessor.DEFAULT_REGENERATE), error);
   }

   @Test
   void extraActionDomainAndAreaFailTheCheck() throws IOException {
      String packDomains = domain("PLANNING", "PLAN", "APPROVE", "CREATE", "DELETE", "LIST", "UPDATE", "VIEW", "PUBLISH")
         + domain("PLATFORM", "PACK_MANIFEST", "LIST", "VIEW");
      Path pack = pack(packDomains);
      Files.writeString(pack.resolve("datasets/functionalAreas.jsonl"),
         "{\"refName\":\"PLANNING\",\"applicationId\":\"acme-planner\"}\n{\"refName\":\"REPORTS\",\"applicationId\":\"acme-planner\"}\n");
      Result result = compile(app(), Map.of(VocabularyProcessor.OPTION_PACK, pack.toString(),
         VocabularyProcessor.OPTION_REGENERATE, "./mvnw compile -Pvocabulary"));
      String error = String.join("\n", result.errors());
      assertFalse(result.success());
      assertTrue(error.contains("extra action PLANNING/PLAN.PUBLISH"), error);
      assertTrue(error.contains("extra domain PLATFORM/PACK_MANIFEST"), error);
      assertTrue(error.contains("missing domain PLATFORM/DEPENDENCY"), error);
      assertTrue(error.contains("missing area PLATFORM"), error);
      assertTrue(error.contains("extra area REPORTS"), error);
      assertTrue(error.contains("./mvnw compile -Pvocabulary"), error);
   }

   @Test
   void writeModeRegeneratesThePackKeepingRowExtras() throws IOException {
      Path pack = pack(domain("PLANNING", "PLAN", "VIEW"));
      Result written = compile(app(), Map.of(VocabularyProcessor.OPTION_PACK, pack.toString(),
         VocabularyProcessor.OPTION_MODE, "write"));
      assertTrue(written.success(), () -> String.join("\n", written.errors()));
      String domains = Files.readString(pack.resolve("datasets/functionalDomains.jsonl"));
      assertTrue(domains.contains("\"tenantId\":\"t1\""), domains);
      assertTrue(domains.contains("\"refName\":\"APPROVE\",\"displayName\":\"Approve\""), domains);
      assertTrue(domains.contains("\"refName\":\"DEPENDENCY\""), domains);
      Result checked = compile(app(), Map.of(VocabularyProcessor.OPTION_PACK, pack.toString()));
      assertTrue(checked.success(), () -> String.join("\n", checked.errors()));
      String again = Files.readString(pack.resolve("datasets/functionalDomains.jsonl"));
      compile(app(), Map.of(VocabularyProcessor.OPTION_PACK, pack.toString(), VocabularyProcessor.OPTION_MODE, "write"));
      assertEquals(again, Files.readString(pack.resolve("datasets/functionalDomains.jsonl")), "regeneration is stable");
   }

   @Test
   void aNewPacksRowsTakeTheApplicationRowsDataDomain() throws IOException {
      Path pack = pack("");
      Files.writeString(pack.resolve("datasets/applications.jsonl"),
         "{\"refName\":\"acme-planner\",\"dataDomain\":{\"tenantId\":\"issuer\"},"
            + "\"uses\":[{\"application\":\"quantum-system\",\"area\":\"SYSTEM\",\"domains\":[\"MENU_ITEM\"]}]}\n");
      Files.writeString(pack.resolve("datasets/functionalAreas.jsonl"), "");
      Result written = compile(app(), Map.of(VocabularyProcessor.OPTION_PACK, pack.toString(),
         VocabularyProcessor.OPTION_MODE, "write"));
      assertTrue(written.success(), () -> String.join("\n", written.errors()));
      for (String file : List.of("datasets/functionalAreas.jsonl", "datasets/functionalDomains.jsonl")) {
         List<String> rows = Files.readAllLines(pack.resolve(file)).stream().filter(l -> !l.isBlank()).toList();
         assertFalse(rows.isEmpty(), file);
         for (String row : rows) {
            assertTrue(row.contains("\"dataDomain\":{\"tenantId\":\"issuer\"}"), file + ": " + row);
         }
      }
   }

   @Test
   void borrowedAreaDomainNotInUsesFailsTheBuild() throws IOException {
      Map<String, String> sources = app();
      sources.put("demo.SettingsResource", """
         package demo;
         import com.e2eq.framework.annotations.FunctionalMapping;
         import jakarta.ws.rs.GET;
         import jakarta.ws.rs.Path;
         @Path("settings") @FunctionalMapping(area = "SYSTEM", domain = "SETTINGS")
         public class SettingsResource { @GET public String get() { return ""; } }
         """);
      Result result = compile(sources, Map.of(VocabularyProcessor.OPTION_PACK, pack(MATCHING).toString()));
      assertFalse(result.success());
      assertTrue(String.join("\n", result.errors()).contains("FUNCTIONAL_MAPPING_UNDECLARED"), result.errors().toString());
   }

   @Test
   void functionalActionWithoutMappingFailsTheBuild() throws IOException {
      Map<String, String> sources = app();
      sources.put("demo.Orphan", """
         package demo;
         import com.e2eq.framework.annotations.FunctionalAction;
         public class Orphan { @FunctionalAction("RUN") public void run() { } }
         """);
      Result result = compile(sources, Map.of(VocabularyProcessor.OPTION_APPLICATION, "acme-planner"));
      assertFalse(result.success());
      assertTrue(String.join("\n", result.errors()).contains("FUNCTIONAL_MAPPING_MISSING"), result.errors().toString());
   }

   @Test
   void unconfiguredProcessorDoesNothing() throws IOException {
      Result result = compile(app(), Map.of());
      assertTrue(result.success(), () -> String.join("\n", result.errors()));
      assertFalse(Files.exists(result.output().resolve(VocabularyManifest.RESOURCE)));
   }
}
