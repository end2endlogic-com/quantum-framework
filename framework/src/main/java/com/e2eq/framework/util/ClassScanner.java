package com.e2eq.framework.util;

import dev.morphia.annotations.Entity;

import io.quarkus.logging.Log;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;


@ApplicationScoped
public class ClassScanner {
   @Inject
   BeanManager beanManager;


   private Set<Class> nonNativeScanForEntityBeans(String packageName) {
      ClassLoader loader = Thread.currentThread().getContextClassLoader();
      InputStream in = loader.getResourceAsStream(packageName.replaceAll("[.]", "/"));
      if (in == null)
      {
         Log.error("Could not find classes with in package:" + packageName);
         return Collections.emptySet();
      }

      BufferedReader reader = new BufferedReader(new InputStreamReader(in));
      Set<Class> classes = reader.lines()
                              .filter(line -> line.endsWith(".class"))
                              .map(line -> getClass(line, packageName))
                              .filter(c -> c.isAnnotationPresent(Entity.class))
                              .collect(Collectors.toSet());

      return classes;
   }

  /* private Set<Class> nativeScanForEntityBeans(String packageName) {
    // Reflections reflections = new Reflections(packageName, new SubTypesScanner(false));
      Reflections reflections = new Reflections(packageName);
      Set<Class<?>> in =  reflections.get(SubTypes.of(BaseModel.class).asClass());

      if (in.isEmpty())
      {
         Log.error("Could not find classes with in package:" + packageName);
         return Collections.emptySet();
      }

      Set<Class> classes = in.stream().filter(c -> c.isAnnotationPresent(Entity.class))
         .collect(Collectors.toSet());

      return classes;
   } */


   public Set<Class> scanForEntityBeans (String packageName) {
      Log.info("Scanning for entity beans in package:" + packageName);

      Set<Class> classes = nonNativeScanForEntityBeans(packageName);
      if (classes.isEmpty()) {
         Log.error(" >> Could not find classes with in package:" + packageName);
         ClassLoader loader = Thread.currentThread().getContextClassLoader();
         String path = packageName.replace(".", "/");
         InputStream in = loader.getResourceAsStream(path);
         BufferedReader reader = new BufferedReader(new InputStreamReader(in));
         reader.lines()
                 .filter(line -> line.endsWith(".class"))
                 .forEach(System.out::println);


      }
      for (Class c : classes) {
         if (c.isAnnotationPresent(Entity.class)) {
            Log.info("   Found entity bean:" + c.getName());
         } else {
            Log.warn("   ** Found class:" + c.getName() + " with no @Entity annotation");
         }
      }
      return classes;
   }

   private Class getClass (String className, String packageName) {
      try {
         return Class.forName(packageName + "."
                                 + className.substring(0, className.lastIndexOf('.')));
      } catch (ClassNotFoundException e) {
         // handle the exception
      }
      return null;
   }
}
