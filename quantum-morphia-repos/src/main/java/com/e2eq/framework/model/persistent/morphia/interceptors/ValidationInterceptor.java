package com.e2eq.framework.model.persistent.morphia.interceptors;

import com.e2eq.framework.exceptions.E2eqValidationException;
import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.general.interfaces.InvalidSavable;

import com.e2eq.framework.model.persistent.base.ValidationViolation;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.morphia.Datastore;
import dev.morphia.EntityListener;
import io.quarkus.logging.Log;
import org.bson.Document;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import org.apache.commons.lang3.reflect.FieldUtils;


@ApplicationScoped
public class ValidationInterceptor implements EntityListener<Object> {
   @Inject
   Validator validator;

   @Inject
   ObjectMapper objectMapper;

   @Inject
   com.e2eq.framework.model.persistent.morphia.interceptors.ddpolicy.DataDomainResolver dataDomainResolver;

   public ValidationInterceptor () {

   }

   @Override
   public boolean hasAnnotation(Class<? extends Annotation> type) {
      return false;
   }

   private Field getFieldFromHierarchy(Class<?> clazz, String fieldName)  throws NoSuchFieldException {
      Field f = FieldUtils.getField(clazz, fieldName, true); // true => force access and search superclasses
      if (f == null) {
         throw new NoSuchFieldException("Field '" + fieldName + "' not found in class hierarchy of " + clazz.getName());
      }
      return f;
   }



   @Override
   public void postLoad (Object ent, Document document, Datastore datastore) {
      if (ent instanceof UnversionedBaseModel) {
         // iterate through the document and validate find fields that are in the document but not in the entity as a property
         for (String fieldName : document.keySet()) {
               if (fieldName.equals("_id")) continue;
               if (fieldName.equals("_t")) continue;

               Object fieldValue = document.get(fieldName);
               if (fieldValue!= null) {
                  try {
                     getFieldFromHierarchy(ent.getClass(), fieldName).getType();
                  } catch (NoSuchFieldException e) {
                     UnversionedBaseModel bm = (UnversionedBaseModel) ent;
                    Log.warnf("Field %s not found in entity %s with id:%s but found in monggodb datastore:%s", fieldName, ent.getClass().getName(), ((UnversionedBaseModel) ent).getId(), datastore.getDatabase().getName());
                    if (bm.getUnmappedProperties() == null ) {
                       bm.setUnmappedProperties(new java.util.HashMap<>());
                    }
                    bm.getUnmappedProperties().put(fieldName, fieldValue);
                  }
               }
         }
      }
   }

   @Override
   public void prePersist (Object ent, Document document, Datastore datastore) {
      UnversionedBaseModel bm = null;
      boolean skipValidation = false;

      if (ent instanceof UnversionedBaseModel) {
         bm = (UnversionedBaseModel) ent;
         if (bm.getUnmappedProperties() != null ) {
            bm.setUnmappedProperties(null);
         }
         if (!bm.isSkipValidation()) {
            if (SecurityContext.getPrincipalContext().isPresent()) {
               if (bm.getDataDomain() == null) {
                  DataDomain dd = dataDomainResolver.resolveForCreate(bm.bmFunctionalArea(), bm.bmFunctionalDomain());
                  if (dd == null) {
                     throw new IllegalStateException("Resolved data domain is null, this should not happen");
                  }
                  bm.setDataDomain(dd);
               }
            }
         } else {
            skipValidation = true;
         }
      }

      if (!skipValidation) {
         final Set<ConstraintViolation<Object>> violationSet = validator.validate(ent);

         if (!violationSet.isEmpty()) {
            Set<ValidationViolation> violations = new HashSet<>(violationSet.size());
            for (ConstraintViolation<?> constraintViolation : violationSet) {
               ValidationViolation violation = new ValidationViolation();

               violation.setPropertyPath(constraintViolation.getPropertyPath().toString());
               violation.setViolationDescription(constraintViolation.getMessage());
               violations.add(violation);
            }

            if (ent instanceof InvalidSavable && ((InvalidSavable) ent).isCanSaveInvalid()) {
               InvalidSavable isaveable = (InvalidSavable) ent;
               isaveable.setViolationSet(null);
               isaveable.setViolationSet(violations);
            } else {
               E2eqValidationException ex = new E2eqValidationException(violationSet, document.toJson().toString());
               ex.setViolationSet(violationSet);
               ex.setJsonData(document.toJson().toString());
               violationSet.forEach(violation -> Log.error(violation.getMessage() + " :" + violation.getPropertyPath() + ":" + violation.getRootBean().getClass().getName()));
               Log.error("Object:");
               try {
                  Log.error(objectMapper.writeValueAsString(ent));
               } catch (JsonProcessingException e) {
                  Log.error("Error processing entity for validation:", e);
                  throw new RuntimeException(e);
               }
               throw ex;
            }
         }
      }
   }
}
