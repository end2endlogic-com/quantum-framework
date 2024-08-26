package com.e2eq.framework.rest.models;


import io.quarkus.runtime.annotations.RegisterForReflection;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;


@RegisterForReflection
public class UIActionList extends ArrayList<UIAction> {

   public UIActionList (int initialCapacity) {
      super(initialCapacity);
   }

   public UIActionList () {
      super();
   }

   public UIActionList (@NotNull Collection<? extends UIAction> c) {
      super(c);
   }

}
