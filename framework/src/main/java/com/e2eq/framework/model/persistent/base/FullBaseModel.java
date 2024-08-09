package com.e2eq.framework.model.persistent.base;

import com.e2eq.framework.model.persistent.interfaces.Archivable;
import com.e2eq.framework.model.persistent.interfaces.Expirable;
import com.e2eq.framework.model.persistent.interfaces.InvalidSavable;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Transient;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.Date;
import java.util.Set;

@Entity
@EqualsAndHashCode (callSuper = true)
@RegisterForReflection
@ToString (callSuper = true)
@NoArgsConstructor
public abstract @Data class FullBaseModel extends BaseModel implements Archivable, InvalidSavable, Expirable {

   protected Date archiveDate;
   protected boolean markedForArchive;
   protected boolean archived;
   protected Date expireDate;
   protected boolean markedForDelete;
   protected boolean expired;
   protected boolean invalid;
   protected boolean canSaveInvalid;

   @Transient
   transient protected boolean saveInvalid=false;

   protected Set<ValidationViolation> violationSet;

}
