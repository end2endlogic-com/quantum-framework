package com.e2eq.framework.imports.service;
import com.e2eq.framework.imports.spi.*;
import com.e2eq.framework.model.persistent.imports.ImportProfile;
import com.e2eq.framework.model.persistent.base.BaseModel;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CalculatorFailureTest {
 @Test void failedCalculatorStopsTheRowBeforeLaterCalculators() {
  var service=new ImportProfileService(); var profile=new ImportProfile(); profile.setFieldCalculatorNames(List.of("derive","later"));
  FieldCalculator failing=mock(FieldCalculator.class), later=mock(FieldCalculator.class);
  when(failing.getName()).thenReturn("derive"); when(later.getName()).thenReturn("later");
  when(failing.appliesTo(any())).thenReturn(true); when(later.appliesTo(any())).thenReturn(true);
  doThrow(new IllegalArgumentException("bad input")).when(failing).calculate(any(),any(),any());
  service.fieldCalculators=mock(Instance.class); when(service.fieldCalculators.iterator()).thenReturn(List.of(failing,later).iterator());
  assertThrows(ImportProfileService.FieldCalculationException.class,()->service.applyFieldCalculators(profile,mock(BaseModel.class),Map.of(),mock(ImportContext.class)));
  verify(later,never()).calculate(any(),any(),any());
 }
}
