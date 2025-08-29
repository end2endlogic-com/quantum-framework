package com.e2eq.framework.rest.models;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Objects;

@RegisterForReflection
public class UIAction {
   protected String label;

   protected String action;
   protected String icon;
   protected String onclick;

   public String getLabel () {
      return label;
   }

   public void setLabel (String label) {
      this.label = label;
   }

   public String getIcon () {
      return icon;
   }

   public void setIcon (String icon) {
      this.icon = icon;
   }

   public String getOnclick () {
      return onclick;
   }

   public void setOnclick (String onclick) {
      this.onclick = onclick;
   }

   public String getAction() {
      return action;
   }

   public void setAction(String action) {
      this.action = action;
   }

   @Override
   public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      UIAction uiAction = (UIAction) o;
      return Objects.equals(label, uiAction.label) && Objects.equals(action, uiAction.action) && Objects.equals(icon, uiAction.icon) && Objects.equals(onclick, uiAction.onclick);
   }

   @Override
   public int hashCode() {
      return Objects.hash(label, action, icon, onclick);
   }
}
