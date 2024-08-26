package com.e2eq.framework.model.persistent.base;

import dev.morphia.annotations.*;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import static dev.morphia.utils.IndexType.DESC;

@Indexes({
   @Index(fields=@Field( value="refName", type=DESC),
      options=@IndexOptions(unique=true)
   )
})
@Entity
@RegisterForReflection
@NoArgsConstructor
@ToString
@EqualsAndHashCode ( callSuper = true)
public @Data class Counter extends BaseModel {

   long currentValue;



   @Override
   public String bmFunctionalArea() {
      return "APP";
   }

   @Override
   public String bmFunctionalDomain() {
      return "COUNTER";
   }


}
