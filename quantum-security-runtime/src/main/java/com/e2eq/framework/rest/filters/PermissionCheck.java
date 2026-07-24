package com.e2eq.framework.rest.filters;

import java.lang.annotation.*;

//@NameBinding
//@Target({ ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PermissionCheck {
   String area() default "";
   String functionalDomain() default "";
   String action() default "";
}
