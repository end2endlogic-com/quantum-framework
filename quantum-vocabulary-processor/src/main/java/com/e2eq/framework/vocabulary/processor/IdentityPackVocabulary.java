package com.e2eq.framework.vocabulary.processor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The vocabulary an identity pack (seed-pack format: {@code manifest.yaml} plus JSONL datasets)
 * declares, read from its {@code application}, {@code functionalArea} and {@code functionalDomain}
 * datasets; the drift between it and a {@link VocabularyManifest}; and the regeneration of those
 * datasets from the manifest.
 *
 * <p>The pack's application row stays hand-written: it carries the roles, owner/member roles,
 * tenancy and {@code uses}. The area and domain datasets are generated from code.
 */
public final class IdentityPackVocabulary {

   static final String COLLECTION_APPLICATION = "application";
   static final String COLLECTION_AREA = "functionalArea";
   static final String COLLECTION_DOMAIN = "functionalDomain";

   private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
   private static final ObjectMapper JSON = new ObjectMapper();
   private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

   /** A borrowed reference as the application row declares it. */
   public record Use(String application, String area, Set<String> domainKeys) {
   }

   private final Path packDir;
   private final String applicationId;
   private final Map<String, Object> application;
   private final Path areaFile;
   private final Path domainFile;
   private final List<Map<String, Object>> areaRows;
   private final List<Map<String, Object>> domainRows;

   private IdentityPackVocabulary(Path packDir, String applicationId, Map<String, Object> application,
                                  Path areaFile, List<Map<String, Object>> areaRows,
                                  Path domainFile, List<Map<String, Object>> domainRows) {
      this.packDir = packDir;
      this.applicationId = applicationId;
      this.application = application;
      this.areaFile = areaFile;
      this.areaRows = areaRows;
      this.domainFile = domainFile;
      this.domainRows = domainRows;
   }

   /**
    * Reads the pack at {@code packDir}. {@code applicationId} picks the application row; when null
    * the pack must hold exactly one.
    */
   public static IdentityPackVocabulary read(Path packDir, String applicationId) throws IOException {
      Path manifestFile = packDir.resolve("manifest.yaml");
      if (!Files.isRegularFile(manifestFile)) {
         throw new IOException("identity pack " + packDir + " has no manifest.yaml");
      }
      Map<String, Object> manifest = YAML.readValue(manifestFile.toFile(), MAP);
      Path applicationFile = null;
      Path areaFile = null;
      Path domainFile = null;
      Object datasets = manifest == null ? null : manifest.get("datasets");
      if (datasets instanceof List<?> list) {
         for (Object item : list) {
            if (!(item instanceof Map<?, ?> dataset)) {
               continue;
            }
            Object collection = dataset.get("collection");
            Object file = dataset.get("file");
            if (!(collection instanceof String c) || !(file instanceof String f)) {
               continue;
            }
            switch (c) {
               case COLLECTION_APPLICATION -> applicationFile = resolve(packDir, f);
               case COLLECTION_AREA -> areaFile = resolve(packDir, f);
               case COLLECTION_DOMAIN -> domainFile = resolve(packDir, f);
               default -> { }
            }
         }
      }
      if (applicationFile == null) {
         throw new IOException("identity pack " + packDir + " declares no '" + COLLECTION_APPLICATION + "' dataset");
      }
      if (domainFile == null) {
         throw new IOException("identity pack " + packDir + " declares no '" + COLLECTION_DOMAIN + "' dataset");
      }
      List<Map<String, Object>> applications = jsonl(applicationFile);
      Map<String, Object> application = pickApplication(packDir, applications, applicationId);
      String id = string(application.get("refName"));
      return new IdentityPackVocabulary(packDir, id, application,
         areaFile, areaFile == null ? List.of() : jsonl(areaFile),
         domainFile, Files.exists(domainFile) ? jsonl(domainFile) : List.of());
   }

   public String applicationId() {
      return applicationId;
   }

   public Path packDir() {
      return packDir;
   }

   /** Role names the application row declares (its {@code roles[].name}). */
   public List<String> roles() {
      List<String> out = new ArrayList<>();
      if (application.get("roles") instanceof List<?> roles) {
         for (Object role : roles) {
            if (role instanceof Map<?, ?> row && row.get("name") instanceof String name && !name.isBlank()) {
               out.add(name.trim());
            }
         }
      }
      return out;
   }

   /** The application row's {@code uses}: borrowed areas keyed lowercase. */
   public Map<String, Use> uses() {
      Map<String, Use> out = new TreeMap<>();
      if (application.get("uses") instanceof List<?> uses) {
         for (Object item : uses) {
            if (!(item instanceof Map<?, ?> row)) {
               continue;
            }
            String from = string(row.get("application"));
            String area = string(row.get("area"));
            if (from == null || area == null) {
               continue;
            }
            Set<String> domains = new TreeSet<>();
            if (row.get("domains") instanceof List<?> list) {
               for (Object domain : list) {
                  String name = string(domain);
                  if (name != null) {
                     domains.add(VocabularyManifest.key(name));
                  }
               }
            }
            out.put(VocabularyManifest.key(area), new Use(from, area, domains));
         }
      }
      return out;
   }

   /** One line per difference between the pack and {@code code}; empty when they agree. */
   public List<String> drift(VocabularyManifest code) {
      List<String> out = new ArrayList<>();
      Map<String, Map<String, Set<String>>> pack = packVocabulary();
      Map<String, Map<String, Set<String>>> generated = new TreeMap<>();
      for (VocabularyManifest.Area area : code.areas()) {
         Map<String, Set<String>> domains = generated.computeIfAbsent(VocabularyManifest.key(area.refName()), k -> new TreeMap<>());
         for (VocabularyManifest.Domain domain : area.domains()) {
            Set<String> actions = new TreeSet<>();
            domain.actions().forEach(a -> actions.add(VocabularyManifest.key(a)));
            domains.put(VocabularyManifest.key(domain.refName()), actions);
         }
      }
      if (areaFile != null) {
         Set<String> packAreas = new TreeSet<>();
         for (Map<String, Object> row : ownRows(areaRows)) {
            String name = string(row.get("refName"));
            if (name != null) {
               packAreas.add(VocabularyManifest.key(name));
            }
         }
         for (String area : generated.keySet()) {
            if (!packAreas.contains(area)) {
               out.add("missing area " + upper(area) + ": code maps it, the pack's " + COLLECTION_AREA + " dataset does not declare it");
            }
         }
         for (String area : packAreas) {
            if (!generated.containsKey(area)) {
               out.add("extra area " + upper(area) + ": the pack declares it, no @FunctionalMapping names it");
            }
         }
      }
      for (Map.Entry<String, Map<String, Set<String>>> area : generated.entrySet()) {
         Map<String, Set<String>> packDomains = pack.getOrDefault(area.getKey(), Map.of());
         for (Map.Entry<String, Set<String>> domain : area.getValue().entrySet()) {
            String where = upper(area.getKey()) + "/" + upper(domain.getKey());
            Set<String> packActions = packDomains.get(domain.getKey());
            if (packActions == null) {
               out.add("missing domain " + where + " " + upperAll(domain.getValue()) + ": code maps it, the pack does not declare it");
               continue;
            }
            for (String action : domain.getValue()) {
               if (!packActions.contains(action)) {
                  out.add("missing action " + where + "." + upper(action) + ": code uses it, the pack does not declare it");
               }
            }
            for (String action : packActions) {
               if (!domain.getValue().contains(action)) {
                  out.add("extra action " + where + "." + upper(action) + ": the pack declares it, no code uses it");
               }
            }
         }
      }
      for (Map.Entry<String, Map<String, Set<String>>> area : pack.entrySet()) {
         Map<String, Set<String>> codeDomains = generated.getOrDefault(area.getKey(), Map.of());
         for (Map.Entry<String, Set<String>> domain : area.getValue().entrySet()) {
            if (!codeDomains.containsKey(domain.getKey())) {
               out.add("extra domain " + upper(area.getKey()) + "/" + upper(domain.getKey()) + " " + upperAll(domain.getValue())
                  + ": the pack declares it, no @FunctionalMapping names it");
            }
         }
      }
      return out;
   }

   /**
    * Rewrites the pack's domain dataset (and its area dataset, when the pack declares one) from
    * {@code code}. Fields other than the vocabulary (display names, dataDomain, status) of rows that
    * already exist are kept; rows tagged for another application are kept unchanged. A new row takes
    * the data domain of the pack's existing rows, or else the application row's.
    *
    * @return the files written
    */
   public List<Path> write(VocabularyManifest code) throws IOException {
      List<Path> written = new ArrayList<>();
      Map<String, Map<String, Object>> existingDomains = new LinkedHashMap<>();
      Object dataDomain = null;
      for (Map<String, Object> row : ownRows(domainRows)) {
         String area = string(row.get("area"));
         String name = string(row.get("refName"));
         if (area != null && name != null) {
            existingDomains.putIfAbsent(VocabularyManifest.key(area) + "/" + VocabularyManifest.key(name), row);
         }
         if (dataDomain == null) {
            dataDomain = row.get("dataDomain");
         }
      }
      for (Map<String, Object> row : ownRows(areaRows)) {
         if (dataDomain == null) {
            dataDomain = row.get("dataDomain");
         }
      }
      if (dataDomain == null && application != null) {
         // A new pack's first rows take the application row's data domain, so they land in the
         // issuer's catalog domain instead of whatever context applies them.
         dataDomain = application.get("dataDomain");
      }
      List<Map<String, Object>> domainsOut = new ArrayList<>();
      for (VocabularyManifest.Area area : code.areas()) {
         for (VocabularyManifest.Domain domain : area.domains()) {
            Map<String, Object> existing = existingDomains.get(VocabularyManifest.key(area.refName()) + "/" + VocabularyManifest.key(domain.refName()));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("refName", domain.refName());
            row.put("displayName", existing != null && existing.get("displayName") instanceof String d ? d : displayName(domain.refName()));
            row.put("applicationId", applicationId);
            row.put("area", area.refName());
            row.put("activeStatus", existing != null && existing.get("activeStatus") != null ? existing.get("activeStatus") : "ACTIVE");
            row.put("functionalActions", actionRows(domain.actions(), existing));
            if (existing != null) {
               for (Map.Entry<String, Object> extra : existing.entrySet()) {
                  row.putIfAbsent(extra.getKey(), extra.getValue());
               }
            } else if (dataDomain != null) {
               row.put("dataDomain", dataDomain);
            }
            domainsOut.add(row);
         }
      }
      domainsOut.addAll(foreignRows(domainRows));
      writeJsonl(domainFile, domainsOut);
      written.add(domainFile);
      if (areaFile != null) {
         Map<String, Map<String, Object>> existingAreas = new LinkedHashMap<>();
         for (Map<String, Object> row : ownRows(areaRows)) {
            String name = string(row.get("refName"));
            if (name != null) {
               existingAreas.putIfAbsent(VocabularyManifest.key(name), row);
            }
         }
         List<Map<String, Object>> areasOut = new ArrayList<>();
         for (VocabularyManifest.Area area : code.areas()) {
            Map<String, Object> existing = existingAreas.get(VocabularyManifest.key(area.refName()));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("refName", area.refName());
            row.put("displayName", existing != null && existing.get("displayName") instanceof String d ? d : displayName(area.refName()));
            row.put("applicationId", applicationId);
            if (existing != null) {
               for (Map.Entry<String, Object> extra : existing.entrySet()) {
                  row.putIfAbsent(extra.getKey(), extra.getValue());
               }
            } else if (dataDomain != null) {
               row.put("dataDomain", dataDomain);
            }
            areasOut.add(row);
         }
         areasOut.addAll(foreignRows(areaRows));
         writeJsonl(areaFile, areasOut);
         written.add(areaFile);
      }
      return written;
   }

   private Map<String, Map<String, Set<String>>> packVocabulary() {
      Map<String, Map<String, Set<String>>> out = new TreeMap<>();
      for (Map<String, Object> row : ownRows(domainRows)) {
         String area = string(row.get("area"));
         String name = string(row.get("refName"));
         if (area == null || name == null) {
            continue;
         }
         Set<String> actions = out.computeIfAbsent(VocabularyManifest.key(area), k -> new TreeMap<>())
            .computeIfAbsent(VocabularyManifest.key(name), k -> new TreeSet<>());
         if (row.get("functionalActions") instanceof List<?> list) {
            for (Object action : list) {
               if (action instanceof Map<?, ?> a && string(a.get("refName")) != null) {
                  actions.add(VocabularyManifest.key(string(a.get("refName"))));
               }
            }
         }
      }
      return out;
   }

   /** Rows of this application; untagged rows count as the pack's own (the loader checks the tag). */
   private List<Map<String, Object>> ownRows(List<Map<String, Object>> rows) {
      List<Map<String, Object>> out = new ArrayList<>();
      for (Map<String, Object> row : rows) {
         String tag = string(row.get("applicationId"));
         if (tag == null || "*".equals(tag) || tag.equalsIgnoreCase(applicationId)) {
            out.add(row);
         }
      }
      return out;
   }

   private List<Map<String, Object>> foreignRows(List<Map<String, Object>> rows) {
      Set<Map<String, Object>> own = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
      own.addAll(ownRows(rows));
      List<Map<String, Object>> out = new ArrayList<>();
      for (Map<String, Object> row : rows) {
         if (!own.contains(row)) {
            out.add(row);
         }
      }
      return out;
   }

   private static List<Object> actionRows(List<String> actions, Map<String, Object> existing) {
      Map<String, String> displayNames = new TreeMap<>();
      if (existing != null && existing.get("functionalActions") instanceof List<?> list) {
         for (Object item : list) {
            if (item instanceof Map<?, ?> a && string(a.get("refName")) != null && a.get("displayName") instanceof String d) {
               displayNames.putIfAbsent(VocabularyManifest.key(string(a.get("refName"))), d);
            }
         }
      }
      List<Object> out = new ArrayList<>();
      for (String action : actions) {
         Map<String, Object> row = new LinkedHashMap<>();
         row.put("refName", action);
         row.put("displayName", displayNames.getOrDefault(VocabularyManifest.key(action), displayName(action)));
         out.add(row);
      }
      return out;
   }

   private static Map<String, Object> pickApplication(Path packDir, List<Map<String, Object>> rows, String applicationId) throws IOException {
      if (applicationId != null && !applicationId.isBlank()) {
         for (Map<String, Object> row : rows) {
            String refName = string(row.get("refName"));
            if (refName != null && refName.equalsIgnoreCase(applicationId.trim())) {
               return row;
            }
         }
         throw new IOException("identity pack " + packDir + " has no application row for '" + applicationId + "'");
      }
      if (rows.size() != 1 || string(rows.get(0).get("refName")) == null) {
         throw new IOException("identity pack " + packDir + " holds " + rows.size()
            + " application rows; name the application with -A" + VocabularyProcessor.OPTION_APPLICATION);
      }
      return rows.get(0);
   }

   /** A dataset file: relative to the pack, or {@code classpath:} resolved against an ancestor of the pack. */
   static Path resolve(Path packDir, String file) throws IOException {
      if (file.startsWith("classpath:")) {
         String relative = file.substring("classpath:".length());
         for (Path dir = packDir.toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
               return candidate;
            }
         }
         throw new IOException("identity pack " + packDir + ": " + file + " is not under any ancestor of the pack");
      }
      if (file.contains("://")) {
         throw new IOException("identity pack " + packDir + ": " + file + " is remote; the vocabulary check needs a local dataset");
      }
      return packDir.resolve(file);
   }

   private static List<Map<String, Object>> jsonl(Path file) throws IOException {
      List<Map<String, Object>> rows = new ArrayList<>();
      int lineNumber = 0;
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
         lineNumber++;
         if (line.isBlank()) {
            continue;
         }
         try {
            rows.add(JSON.readValue(line, MAP));
         } catch (IOException e) {
            throw new IOException(file + " line " + lineNumber + " is not a JSON object: " + e.getMessage(), e);
         }
      }
      return rows;
   }

   private static void writeJsonl(Path file, List<Map<String, Object>> rows) throws IOException {
      StringBuilder out = new StringBuilder();
      for (Map<String, Object> row : rows) {
         out.append(JSON.writeValueAsString(row)).append('\n');
      }
      Files.createDirectories(Objects.requireNonNull(file.toAbsolutePath().getParent()));
      Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
   }

   static String displayName(String refName) {
      StringBuilder display = new StringBuilder();
      for (String part : refName.split("[_\\-\\s]+")) {
         if (part.isBlank()) {
            continue;
         }
         if (display.length() > 0) {
            display.append(' ');
         }
         display.append(Character.toUpperCase(part.charAt(0)));
         if (part.length() > 1) {
            display.append(part.substring(1).toLowerCase(Locale.ROOT));
         }
      }
      return display.toString();
   }

   private static String string(Object value) {
      return value instanceof String s && !s.isBlank() ? s.trim() : null;
   }

   private static String upper(String key) {
      return key.toUpperCase(Locale.ROOT);
   }

   private static String upperAll(Set<String> keys) {
      Set<String> out = new LinkedHashSet<>();
      keys.forEach(k -> out.add(upper(k)));
      return out.toString();
   }

   Optional<Path> areaFile() {
      return Optional.ofNullable(areaFile);
   }
}
