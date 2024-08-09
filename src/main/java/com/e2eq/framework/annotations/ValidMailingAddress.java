package com.e2eq.framework.annotations;

import com.e2eq.framework.util.MailingAddressValidator;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {MailingAddressValidator.class})
@Documented
public @interface ValidMailingAddress {
    String message() default "{com.b2bi.framework.v1.model.validator.ValidMailingAddress.message}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

}
