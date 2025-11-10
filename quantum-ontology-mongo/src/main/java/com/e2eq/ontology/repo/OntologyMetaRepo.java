package com.e2eq.ontology.repo;

import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import com.e2eq.ontology.model.OntologyMeta;
import dev.morphia.query.Query;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Date;
import java.util.Optional;

@ApplicationScoped
public class OntologyMetaRepo extends MorphiaRepo<OntologyMeta> {

    public Optional<OntologyMeta> getSingleton() {
        Query<OntologyMeta> q = ds().find(OntologyMeta.class)
                .filter(Filters.eq("refName", "global"));
        return Optional.ofNullable(q.first());
    }

    public OntologyMeta upsert(String yamlHash, Integer yamlVersion, String source, boolean reindexRequired) {
        OntologyMeta meta = getSingleton().orElseGet(() -> {
            OntologyMeta m = new OntologyMeta();
            m.setRefName("global");
            return m;
        });
        meta.setYamlHash(yamlHash);
        meta.setYamlVersion(yamlVersion);
        meta.setSource(source);
        meta.setUpdatedAt(new Date());
        meta.setReindexRequired(reindexRequired);
        save(ds(), meta);
        return meta;
    }

    public void saveSingleton(OntologyMeta m) {
        save(ds(), m);
    }

    private dev.morphia.Datastore ds() {
        return morphiaDataStore.getDataStore(defaultRealm);
    }
}
