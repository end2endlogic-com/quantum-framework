package com.e2eq.framework.test;

import com.e2eq.framework.annotations.StateGraph;
import com.e2eq.framework.annotations.Stateful;
import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
@EqualsAndHashCode( callSuper = true)
@ToString( callSuper = true)
@Entity
@RegisterForReflection
@Stateful
public class TestOrder extends BaseModel {

   protected String orderId;

   @StateGraph(graphName = "orderStringState")
   protected String orderStatus;

   @Override
   public String bmFunctionalArea () {
      return "TEST";
   }

   @Override
   public String bmFunctionalDomain () {
      return "Order";
   }
}
