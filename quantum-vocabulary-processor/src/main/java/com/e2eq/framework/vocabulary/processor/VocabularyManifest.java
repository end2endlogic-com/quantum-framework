package com.e2eq.framework.vocabulary.processor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * An application's functional vocabulary as its code declares it: the areas, domains and actions
 * its {@code @FunctionalMapping}/{@code @FunctionalAction} code names, the domains it borrows from
 * other applications ({@code uses}), and the roles its declaration names.
 *
 * <p>Deterministic: names compare case-insensitively (policy headers lowercase every axis), every
 * list is sorted by that key, and one spelling is chosen per key independent of discovery order.
 * {@link #contentHash()} covers the vocabulary and roles only, not {@code sources}, so moving an
 * annotation between classes does not change the hash.
 *
 * <p>Format ({@value #SCHEMA}), written to {@value #RESOURCE}:
 * <pre>
 * {
 *   "schema": "quantum.vocabulary.v1",
 *   "applicationId": "acme-planner",
 *   "contentHash": "sha256:…",
 *   "areas": [ { "refName": "PLANNING", "domains": [ { "refName": "PLAN", "actions": ["CREATE", …] } ] } ],
 *   "uses":  [ { "application": "quantum-system", "area": "SYSTEM",
 *                "domains": [ { "refName": "MENU_ITEM", "actions": ["VIEW"] } ] } ],
 *   "roles": ["planner", …],
 *   "sources": { "PLANNING/PLAN": ["com.acme.Plan (model)", "com.acme.PlanResource#approve"] }
 * }
 * </pre>
 */
public final class VocabularyManifest {

   public static final String SCHEMA = "quantum.vocabulary.v1";
   public static final String RESOURCE = "META-INF/quantum/vocabulary.json";

   private static final ObjectMapper JSON = new ObjectMapper()
      .enable(SerializationFeature.INDENT_OUTPUT);

   /** One domain: its spelling and its actions, keyed lowercase. */
   public record Domain(String refName, List<String> actions) {
      public Domain {
         actions = List.copyOf(actions);
      }
   }

   public record Area(String refName, List<Domain> domains) {
      public Area {
         domains = List.copyOf(domains);
      }
   }

   public record Borrowed(String application, String area, List<Domain> domains) {
      public Borrowed {
         domains = List.copyOf(domains);
      }
   }

   private final String applicationId;
   private final List<Area> areas;
   private final List<Borrowed> uses;
   private final List<String> roles;
   private final Map<String, List<String>> sources;
   private final String contentHash;

   private VocabularyManifest(String applicationId, List<Area> areas, List<Borrowed> uses, List<String> roles,
                              Map<String, List<String>> sources) {
      this.applicationId = applicationId;
      this.areas = List.copyOf(areas);
      this.uses = List.copyOf(uses);
      this.roles = List.copyOf(roles);
      this.sources = sources;
      this.contentHash = "sha256:" + sha256(canonical(false));
   }

   public String applicationId() {
      return applicationId;
   }

   public List<Area> areas() {
      return areas;
   }

   public List<Borrowed> uses() {
      return uses;
   }

   public List<String> roles() {
      return roles;
   }

   public String contentHash() {
      return contentHash;
   }

   /** The manifest as written: pretty-printed, stable key order, trailing newline. */
   public String toJson() {
      return canonical(true) + "\n";
   }

   private String canonical(boolean full) {
      Map<String, Object> root = new LinkedHashMap<>();
      root.put("schema", SCHEMA);
      root.put("applicationId", applicationId);
      if (full) {
         root.put("contentHash", contentHash);
      }
      List<Object> areaRows = new ArrayList<>();
      for (Area area : areas) {
         Map<String, Object> row = new LinkedHashMap<>();
         row.put("refName", area.refName());
         row.put("domains", domainRows(area.domains()));
         areaRows.add(row);
      }
      root.put("areas", areaRows);
      List<Object> useRows = new ArrayList<>();
      for (Borrowed borrowed : uses) {
         Map<String, Object> row = new LinkedHashMap<>();
         row.put("application", borrowed.application());
         row.put("area", borrowed.area());
         row.put("domains", domainRows(borrowed.domains()));
         useRows.add(row);
      }
      root.put("uses", useRows);
      root.put("roles", roles);
      if (full) {
         root.put("sources", sources);
      }
      try {
         return full ? JSON.writeValueAsString(root) : new ObjectMapper().writeValueAsString(root);
      } catch (JsonProcessingException e) {
         throw new IllegalStateException("vocabulary manifest does not serialize: " + e.getMessage(), e);
      }
   }

   private static List<Object> domainRows(List<Domain> domains) {
      List<Object> rows = new ArrayList<>();
      for (Domain domain : domains) {
         Map<String, Object> row = new LinkedHashMap<>();
         row.put("refName", domain.refName());
         row.put("actions", domain.actions());
         rows.add(row);
      }
      return rows;
   }

   static String key(String value) {
      return value.trim().toLowerCase(Locale.ROOT);
   }

   private static String sha256(String text) {
      try {
         MessageDigest digest = MessageDigest.getInstance("SHA-256");
         return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
      } catch (NoSuchAlgorithmException e) {
         throw new IllegalStateException("SHA-256 unavailable", e);
      }
   }

   /** Collects vocabulary in any order; {@link #build()} sorts and picks one spelling per key. */
   public static final class Builder {
      private final String applicationId;
      // key(area) -> key(domain) -> collected
      private final Map<String, Collected> owned = new TreeMap<>();
      private final Map<String, Collected> borrowed = new TreeMap<>(); // key(app)/key(area)
      private final Set<String> roles = new TreeSet<>();

      public Builder(String applicationId) {
         this.applicationId = Objects.requireNonNull(applicationId, "applicationId").trim();
      }

      /** An owned area/domain, with zero or more actions, named at {@code source}. */
      public Builder owned(String area, String domain, String action, String source) {
         owned.computeIfAbsent(key(area), k -> new Collected()).add(area, domain, action, source);
         return this;
      }

      public Builder borrowed(String application, String area, String domain, String action, String source) {
         Collected collected = borrowed.computeIfAbsent(key(application) + "/" + key(area), k -> new Collected());
         collected.application.add(application.trim());
         collected.add(area, domain, action, source);
         return this;
      }

      public Builder role(String role) {
         if (role != null && !role.isBlank()) {
            roles.add(role.trim());
         }
         return this;
      }

      public VocabularyManifest build() {
         List<Area> areas = new ArrayList<>();
         Map<String, List<String>> sources = new TreeMap<>();
         for (Collected area : owned.values()) {
            areas.add(new Area(area.spelling(), area.domains("", sources)));
         }
         List<Borrowed> uses = new ArrayList<>();
         for (Collected area : borrowed.values()) {
            uses.add(new Borrowed(area.application.first(), area.spelling(),
               area.domains(area.application.first() + ":", sources)));
         }
         // Case-insensitive de-duplication of role names with a stable spelling.
         Map<String, TreeSet<String>> roleSpellings = new TreeMap<>();
         for (String role : roles) {
            roleSpellings.computeIfAbsent(key(role), k -> new TreeSet<>()).add(role);
         }
         List<String> roleList = roleSpellings.values().stream().map(TreeSet::first).toList();
         return new VocabularyManifest(applicationId, areas, uses, roleList, sources);
      }
   }

   private static final class Collected {
      final TreeSet<String> application = new TreeSet<>();
      final TreeSet<String> spellings = new TreeSet<>();
      // key(domain) -> spellings, actions key -> spellings, sources
      final Map<String, TreeSet<String>> domainSpellings = new TreeMap<>();
      final Map<String, Map<String, TreeSet<String>>> actions = new TreeMap<>();
      final Map<String, TreeSet<String>> sources = new TreeMap<>();

      void add(String area, String domain, String action, String source) {
         spellings.add(area.trim());
         String domainKey = key(domain);
         domainSpellings.computeIfAbsent(domainKey, k -> new TreeSet<>()).add(domain.trim());
         Map<String, TreeSet<String>> domainActions = actions.computeIfAbsent(domainKey, k -> new TreeMap<>());
         if (action != null && !action.isBlank()) {
            domainActions.computeIfAbsent(key(action), k -> new TreeSet<>()).add(action.trim());
         }
         if (source != null) {
            sources.computeIfAbsent(domainKey, k -> new TreeSet<>()).add(source);
         }
      }

      String spelling() {
         return spellings.first();
      }

      List<Domain> domains(String prefix, Map<String, List<String>> sourcesOut) {
         List<Domain> out = new ArrayList<>();
         for (Map.Entry<String, TreeSet<String>> entry : domainSpellings.entrySet()) {
            List<String> actionList = actions.get(entry.getKey()).values().stream().map(TreeSet::first).toList();
            Domain domain = new Domain(entry.getValue().first(), actionList);
            out.add(domain);
            TreeSet<String> from = sources.get(entry.getKey());
            if (from != null) {
               sourcesOut.put(prefix + spelling() + "/" + domain.refName(), List.copyOf(from));
            }
         }
         return out;
      }
   }
}
