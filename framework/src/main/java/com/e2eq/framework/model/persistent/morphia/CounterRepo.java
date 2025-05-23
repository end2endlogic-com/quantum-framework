package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.Counter;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.securityrules.SecurityContext;
import dev.morphia.Datastore;

import dev.morphia.query.filters.Filters;
import dev.morphia.query.updates.UpdateOperators;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;


@ApplicationScoped
public class CounterRepo extends MorphiaRepo<Counter> {

    public long getAndIncrement(String name) {
        Datastore ds = morphiaDataStore.getDataStore(getSecurityContextRealmId());
        return getAndIncrement(ds, name, SecurityContext.getPrincipalDataDomain().get(), 1);
    }


    public long getAndIncrement(@NotNull Datastore ds, @NotNull @NotEmpty  String name, @Valid DataDomain dataDomain,
                                long incrementAmount){

        Counter v = ds.find(Counter.class).filter(Filters.eq("refName", name),
                        Filters.eq("dataDomain.accountNum",dataDomain.getAccountNum()),
                        Filters.eq("dataDomain.tenantId", dataDomain.getTenantId()),
                        // in this case we should not care about an ownerId, and there should not
                        // be two counters with the same name for the same dataDomain.
                        //  Filters.eq("dataDomain.ownerId", dataDomain.getOwnerId()),
                        Filters.eq("dataDomain.orgRefName", dataDomain.getOrgRefName()),
                        Filters.eq("dataDomain.dataSegment", dataDomain.getDataSegment()))
                .modify(UpdateOperators.inc("currentValue", incrementAmount));

        if (v == null) {
            v = new Counter();
            v.setDisplayName(name);
            v.setRefName(name);
            v.setCurrentValue(0);
            v.setDataDomain(dataDomain);
            v = save(ds, v);
        }
        return v.getCurrentValue();
    }

   public long getAndIncrement(@NotNull @NotEmpty  String name, @Valid DataDomain dataDomain,
                               long incrementAmount){
      Datastore ds = morphiaDataStore.getDataStore(getSecurityContextRealmId());
      return getAndIncrement(ds, name, dataDomain, incrementAmount);
   }
}
