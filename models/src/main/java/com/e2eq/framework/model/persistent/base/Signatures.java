package com.e2eq.framework.model.persistent.base;

import dev.morphia.annotations.Entity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Data
@EqualsAndHashCode
@ToString
@NoArgsConstructor
public class Signatures {
   String referencesSignature;
   String auditInfoSignature;
   String entityHash;
}
