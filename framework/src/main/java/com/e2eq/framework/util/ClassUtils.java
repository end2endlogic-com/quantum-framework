package com.e2eq.framework.util;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

public abstract class ClassUtils {
   static public ParameterizedType getParameterizedType(Class<?> target) {
      Type[] types = getGenericType(target);
      if (types.length > 0 && types[0] instanceof ParameterizedType) {
         return (ParameterizedType) types[0];
      }
      return null;
   }

   static public Type[] getParameterizedTypes(Class<?> target) {
      Type[] types = getGenericType(target);
      if (types.length > 0 && types[0] instanceof ParameterizedType) {
         return ((ParameterizedType) types[0]).getActualTypeArguments();
      }
      return null;
   }

   static public Type[] getGenericType(Class<?> target) {
      if (target == null)
         return new Type[0];
      Type[] types = target.getGenericInterfaces();
      if (types.length > 0) {
         return types;
      }
      Type type = target.getGenericSuperclass();
      if (type != null) {
         if (type instanceof ParameterizedType) {
            return new Type[] { type };
         }
      }
      return new Type[0];
   }

}
