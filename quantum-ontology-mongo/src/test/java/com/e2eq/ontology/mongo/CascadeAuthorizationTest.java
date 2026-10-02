package com.e2eq.ontology.mongo;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.ontology.repo.OntologyEdgeRepo;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.mockito.Mockito.*;
class CascadeAuthorizationTest {
 @Test void missingCascadeDeclarationNeverVisitsOutgoingTargets() throws Exception {
  var executor=new CascadeExecutor(); executor.edgeRepo=mock(OntologyEdgeRepo.class);
  var field=CascadeExecutor.class.getDeclaredField("ontologyTypeToClass"); field.setAccessible(true);
  ((Map<String,Class<?>>)field.get(executor)).put("NoCascade",Object.class);
  executor.onAfterDelete(new DataDomain("org","account","tenant",0,"user"),"NoCascade","source");
  verifyNoInteractions(executor.edgeRepo);
 }
}
