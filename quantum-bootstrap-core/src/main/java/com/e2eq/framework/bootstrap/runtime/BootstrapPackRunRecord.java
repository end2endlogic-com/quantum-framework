package com.e2eq.framework.bootstrap.runtime;

import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.annotations.FunctionalMapping;
import dev.morphia.annotations.*;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** Storage representation of the existing bootstrap run, not a second execution ledger. */
@Entity(value="bootstrap_pack_runs",useDiscriminator=false)
@Indexes(@Index(fields=@Field("refName"),options=@IndexOptions(unique=true,name="uidx_bootstrap_execution_key")))
@FunctionalMapping(area="SYSTEM",domain="BOOTSTRAP_PACK")
@Data
@EqualsAndHashCode(callSuper=true)
@NoArgsConstructor
@RegisterForReflection
public class BootstrapPackRunRecord extends BaseModel {
    private String runJson;
}
