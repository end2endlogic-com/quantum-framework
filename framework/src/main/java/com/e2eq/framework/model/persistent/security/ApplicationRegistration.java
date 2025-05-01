package com.e2eq.framework.model.persistent.security;

import com.e2eq.framework.model.persistent.base.FullBaseModel;
import com.e2eq.framework.rest.models.UIAction;
import com.e2eq.framework.rest.models.UIActionList;
import dev.morphia.annotations.*;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.commons.text.WordUtils;
import org.bson.codecs.pojo.annotations.BsonIgnore;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static dev.morphia.mapping.IndexType.DESC;


@Indexes({
   @Index(fields=@Field( value="refName", type=DESC),
      options=@IndexOptions(unique=true)
   )
})
@Entity()
@RegisterForReflection
@Data
@EqualsAndHashCode(callSuper = true)
public class ApplicationRegistration extends FullBaseModel {

   public enum Status {
      APPROVED, PENDING, EXPIRED, REJECTED
   };

   protected @NotNull String fname;
   protected @NotNull String lname;
   protected @NotNull @Email String userEmail;
   protected @NotNull String userTelephone;
   protected @NotNull String userId;
   protected @NotNull String password;
   protected @NotNull String companyName;
   protected @NotNull String companyIdentifier;
   protected Date registerDate;
   protected @NotNull Status status;

   protected boolean terms;
   protected String termsAgreement;

   public ApplicationRegistration () {
      super();
      registerDate = new Date();
      status = Status.PENDING;
   }

   @BsonIgnore
   public String getUserName () {
      return fname + " " + lname;
   }



   @Override
   public String bmFunctionalArea() {
      return "SECURITY";
   }

   @Override
   public String bmFunctionalDomain() {
      return "APPLICATION_REGISTRATION";
   }

   @Override
   public UIActionList calculateStateBasedUIActions () {

      Map<String, UIAction> actionsMap = new HashMap<>(defaultUIActions().size());


      for ( String defaultAction : defaultUIActions() ) {
         UIAction action = new UIAction();
         action.setLabel(WordUtils.capitalize(defaultAction.toLowerCase().replace("_", " ")));
         action.setAction(defaultAction);

         switch (defaultAction) {
            case "APPROVE":
               action.setOnclick("rowActionApprove()");
               action.setIcon(" pi pi-check-square");
               break;
            case "REJECT":
               action.setOnclick("rowActionReject()");
               action.setIcon(" pi pi-delete-left");
               break;
            default:
               break;
         }

         actionsMap.put(defaultAction, action);
      }

      if (this.getId() != null ) {
         actionsMap.remove("CREATE");
      }

      if (!this.getStatus().equals(Status.PENDING)) {
         actionsMap.remove("APPROVE");
         actionsMap.remove( "REJECT");
      }

      UIActionList list  = new UIActionList(actionsMap.values());
      list.sort( (a, b) -> a.getLabel().compareToIgnoreCase(b.getLabel()) );
      return list;

   }

}
