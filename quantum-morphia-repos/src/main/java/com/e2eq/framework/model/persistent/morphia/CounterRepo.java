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

    public static boolean isValidBase(Integer base) {
        if (base == null) return false;
        int b = base.intValue();
        return b >= Character.MIN_RADIX && b <= Character.MAX_RADIX;
    }

    public static String encode(long value, int base) {
        return Long.toString(value, base);
    }

    public long getAndIncrement(String name) {
        Datastore ds = morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId());
        return getAndIncrement(ds, name, SecurityContext.getPrincipalDataDomain().get(), 1);
    }


    public long getAndIncrement(@NotNull Datastore ds, @NotNull @NotEmpty  String name, @Valid DataDomain dataDomain,
                                long incrementAmount){

        // Option 2: use a transactional approach to ensure an ObjectId is created on save,
        // then perform the increment and return the previous value atomically.
        return ds.withTransaction(session -> {
            // Find existing counter within the transaction
            Counter existing = ds.find(Counter.class).filter(
                    Filters.eq("refName", name),
                    Filters.eq("dataDomain.accountNum", dataDomain.getAccountNum()),
                    Filters.eq("dataDomain.tenantId", dataDomain.getTenantId()),
                    Filters.eq("dataDomain.orgRefName", dataDomain.getOrgRefName()),
                    Filters.eq("dataDomain.dataSegment", dataDomain.getDataSegment())
            ).first();

            long previous;
            if (existing == null) {
                // Create the counter initialized at 0 with required fields
                Counter created = new Counter();
                created.setDisplayName(name);
                created.setRefName(name);
                created.setCurrentValue(0);
                created.setDataDomain(dataDomain);
                created = save(ds, created);
                previous = 0L;
            } else {
                previous = existing.getCurrentValue();
            }

            // Now increment currentValue
            ds.find(Counter.class).filter(
                    Filters.eq("refName", name),
                    Filters.eq("dataDomain.accountNum", dataDomain.getAccountNum()),
                    Filters.eq("dataDomain.tenantId", dataDomain.getTenantId()),
                    Filters.eq("dataDomain.orgRefName", dataDomain.getOrgRefName()),
                    Filters.eq("dataDomain.dataSegment", dataDomain.getDataSegment())
            ).modify(UpdateOperators.inc("currentValue", incrementAmount));

            return previous;
        });
    }

   public long getAndIncrement(@NotNull @NotEmpty  String name, @Valid DataDomain dataDomain,
                               long incrementAmount){
      Datastore ds = morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId());
      return getAndIncrement(ds, name, dataDomain, incrementAmount);
   }

   public CounterValue getAndIncrementEncoded(@NotNull @NotEmpty String name,
                                              @Valid DataDomain dataDomain,
                                              long incrementAmount,
                                              Integer base) {
      Datastore ds = morphiaDataStoreWrapper.getDataStore(getSecurityContextRealmId());
      long value = getAndIncrement(ds, name, dataDomain, incrementAmount);
      if (base == null) {
         return new CounterValue(value);
      }
      if (!isValidBase(base)) {
         throw new IllegalArgumentException("Invalid base; supported range is 2-36");
      }
      return new CounterValue(value, base, encode(value, base));
   }
}
