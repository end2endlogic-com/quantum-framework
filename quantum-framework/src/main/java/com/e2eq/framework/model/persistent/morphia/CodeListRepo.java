package com.e2eq.framework.model.persistent.morphia;


import com.e2eq.framework.model.persistent.base.CodeList;
import dev.morphia.query.Query;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

@ApplicationScoped
public class CodeListRepo extends MorphiaRepo<CodeList> {


    public Optional<CodeList> findByCategoryAndKey(String category, String key) {
            Query<CodeList> query = morphiaDataStore.getDataStore(getSecurityContextRealmId())
                    .find(CodeList.class)
                    .filter(Filters.eq("category", category))
                    .filter(Filters.eq("key", key));

            return Optional.ofNullable(query.first());
    }

}
