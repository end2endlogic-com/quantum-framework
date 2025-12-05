package com.e2eq.framework.service.seed;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.general.SeedHistory;
import com.e2eq.framework.model.persistent.morphia.BaseMorphiaRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import dev.morphia.Datastore;
import dev.morphia.query.filters.Filters;
import io.quarkus.arc.Arc;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.MessageDigest;// Or use java.security.MessageDigest

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
/* just an experiment for now ignore */
//@ApplicationScoped
public class SmartSeedLoader {

   @Inject ObjectMapper jsonMapper;
   @Inject Datastore datastore; // Morphia Datastore for history checks

   private final ObjectMapper yamlMapper = new YAMLMapper();

   public void applyManifest(String manifestPath, String realmId) {
      try (InputStream is = loadResource(manifestPath)) {
         SeedPackManifest manifest = yamlMapper.readValue(is, SeedPackManifest.class);
         Log.infof("Checking SeedPack: %s [%s]", manifest.getSeedPack(), realmId);

         for (SeedPackManifest.Dataset dataset : manifest.getDatasets()) {
            applyDataset(manifest.getSeedPack(), dataset, realmId);
         }
      } catch (Exception e) {
         throw new RuntimeException("Error processing manifest: " + manifestPath, e);
      }
   }

   private void applyDataset(String packName, SeedPackManifest.Dataset dataset, String realmId) throws Exception {
      // 1. Calculate Hash of the source file
      String currentHash;
      try (InputStream is = loadResource(dataset.getFile())) {
         // use MessageDigest to calculate a SHA-256 hash
         MessageDigest md = MessageDigest.getInstance("SHA-256");
         byte[] bytes = is.readAllBytes();
         currentHash = java.util.HexFormat.of().formatHex(md.digest(bytes));
      }

      // 2. Check History (Governance)
      SeedHistory history = datastore.find(SeedHistory.class)
                               .filter(Filters.eq("seedPackName", packName))
                               .filter(Filters.eq("datasetCollection", dataset.getCollection()))
                               .first();

      if (history != null && currentHash.equals(history.getFileChecksum())) {
         Log.debugf("Skipping %s - Checksum matches (Already up to date)", dataset.getCollection());
         return;
      }

      // 3. Resolve Repo & Class
      Class<?> modelClass = Class.forName(dataset.getModelClass());
      Class<?> repoClass = Class.forName(dataset.getRepoClass());
      // Dynamic CDI lookup - finds the bean instance
      BaseMorphiaRepo repo = (BaseMorphiaRepo) Arc.container().select(repoClass).get();

      Log.infof("Applying changes to %s...", dataset.getCollection());

      // 4. Read & Upsert
      int count = 0;
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(loadResource(dataset.getFile()), StandardCharsets.UTF_8))) {
         String line;
         while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;

            // Deserialize directly to POJO (handles Enums/Dates via Jackson annotations)
            Object entity = jsonMapper.readValue(line, modelClass);

            // Perform the "Merge/Save" with aligned generics
            @SuppressWarnings("unchecked")
            BaseMorphiaRepo<UnversionedBaseModel> typedRepo = (BaseMorphiaRepo<UnversionedBaseModel>) repo;
            @SuppressWarnings("unchecked")
            UnversionedBaseModel typedEntity = (UnversionedBaseModel) entity;
            upsert(typedRepo, typedEntity, dataset.getNaturalKey());
            count++;

         }
      }

      // 5. Update History
      if (history == null) {
         history = new SeedHistory(packName, dataset.getCollection(), currentHash, count);
      } else {
         // Update existing history record
         history = new SeedHistory(packName, dataset.getCollection(), currentHash, count);
         // Note: In real logic, you'd probably update the fields of the existing object to keep the ID
      }
      datastore.save(history);
      Log.infof("Finished %s. Upserted %d records.", dataset.getCollection(), count);
   }

   /**
    * The core logic: Look up by natural key. If found, grab ID -> set on new object -> Save.
    */
   // Replace the current upsert signature and body with this generic version:
   private <T extends UnversionedBaseModel> void upsert(BaseMorphiaRepo<T> repo, T entity, List<String> naturalKeys) {
      if (naturalKeys == null || naturalKeys.isEmpty()) {
         repo.save(entity);
         return;
      }

      var query = repo.getMorphiaDataStore().find(entity.getClass());
      Map<String, Object> entityMap = jsonMapper.convertValue(entity, Map.class);

      for (String key : naturalKeys) {
         query.filter(Filters.eq(key, entityMap.get(key)));
      }

      Object existing = query.first();
      if (existing != null) {
         try {
            try {
               var getId = existing.getClass().getMethod("getId");
               Object existingId = getId.invoke(existing);
               var setId = entity.getClass().getMethod("setId", existingId.getClass());
               setId.invoke(entity, existingId);
            } catch (NoSuchMethodException nsme) {
               var idField = existing.getClass().getDeclaredField("id");
               idField.setAccessible(true);
               Object existingId = idField.get(existing);
               var targetField = entity.getClass().getDeclaredField("id");
               targetField.setAccessible(true);
               targetField.set(entity, existingId);
            }
         } catch (Exception e) {
            Log.error("Could not copy ID for upsert", e);
         }
      }
      repo.save(entity);
   }

   private InputStream loadResource(String path) {
      InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
      if (is == null) throw new RuntimeException("File not found: " + path);
      return is;
   }
}
