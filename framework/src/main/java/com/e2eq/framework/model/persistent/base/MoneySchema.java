package com.e2eq.framework.model.persistent.base;

import dev.morphia.annotations.Entity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
@EqualsAndHashCode
@ToString
@Entity
public class MoneySchema {
    double amount;
    String currency;
}
