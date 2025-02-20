package com.e2eq.framework.model.persistent.base;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
@EqualsAndHashCode
@ToString
public class MoneySchema {
    double amount;
    String currency;
}