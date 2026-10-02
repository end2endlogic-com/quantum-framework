package com.e2eq.framework.model.persistent.morphia;
import com.e2eq.framework.model.persistent.base.*;
import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import dev.morphia.Datastore;
import dev.morphia.query.Query;
import dev.morphia.transactions.MorphiaSession;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ReferenceDeletionTest {
 static class Model extends UnversionedBaseModel {
  public String bmFunctionalArea(){return "test";} public String bmFunctionalDomain(){return "test";}
 }
 static class Repo extends MorphiaRepo<Model> {
  Model model;
  @Override public Optional<Model> findById(ObjectId id,String realm){return Optional.of(model);}
  @Override public Optional<Model> findById(Datastore session,ObjectId id){return Optional.of(model);}
 }
 @Test void mixedStaleAndLiveReferencesNeverDeleteInEitherOrder() throws Exception {
  for (boolean staleFirst : new boolean[]{true,false}) {
   var repo=new Repo(); var target=new Model(); target.setId(new ObjectId()); repo.model=target;
   target.setReferences(new LinkedHashSet<>(List.of(new ReferenceEntry(new ObjectId(),Model.class.getName(),"first"),new ReferenceEntry(new ObjectId(),Model.class.getName(),"second"))));
   var session=mock(dev.morphia.transactions.SessionDatastore.class); var datastore=mock(dev.morphia.MorphiaDatastore.class);
   repo.morphiaDataStoreWrapper=mock(MorphiaDataStoreWrapper.class);
   when(repo.morphiaDataStoreWrapper.getDataStore("realm")).thenReturn(datastore);
   when(datastore.startSession()).thenReturn(session);
   Query<Model> query=mock(Query.class,RETURNS_SELF); when(session.find(Model.class)).thenReturn(query);
   when(query.count()).thenReturn(staleFirst?0L:1L,staleFirst?1L:0L);
   assertThrows(ReferentialIntegrityViolationException.class,()->repo.delete("realm",target.getId()));
   verify(session,never()).delete(any(Model.class)); verify(session,never()).commitTransaction(); verify(session).abortTransaction();
  }
 }
}
