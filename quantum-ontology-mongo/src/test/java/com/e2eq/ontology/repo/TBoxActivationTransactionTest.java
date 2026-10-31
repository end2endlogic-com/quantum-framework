package com.e2eq.ontology.repo;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.ontology.model.TenantOntologyTBox;
import com.mongodb.client.MongoClients;
import dev.morphia.Datastore;
import dev.morphia.Morphia;
import dev.morphia.transactions.MorphiaSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class TBoxActivationTransactionTest {
 static class Repo extends TenantOntologyTBoxRepo {
  boolean failSave;
  Repo(Datastore ds) { morphiaDataStoreWrapper=mock(MorphiaDataStoreWrapper.class); when(morphiaDataStoreWrapper.getDataStore(anyString())).thenReturn((dev.morphia.MorphiaDatastore)ds); }
  @Override public String getSecurityContextRealmId(){return "review";}
  @Override public TenantOntologyTBox save(MorphiaSession session,TenantOntologyTBox target) {
   if(failSave)throw new IllegalStateException("injected write failure");
   return session.save(target);
  }
 }
 @Test void activationRollsBackAndConcurrentFirstActivationsCannotCreateTwoActives() throws Exception {
  String uri=System.getProperty("quantum.review.mongo-uri"); Assumptions.assumeTrue(uri!=null,"isolated replica-set URI required");
  try(var client=MongoClients.create(com.mongodb.MongoClientSettings.builder()
    .applyConnectionString(new com.mongodb.ConnectionString(uri))
    .codecRegistry(org.bson.codecs.configuration.CodecRegistries.fromRegistries(
        com.mongodb.MongoClientSettings.getDefaultCodecRegistry(),
        org.bson.codecs.configuration.CodecRegistries.fromProviders(org.bson.codecs.pojo.PojoCodecProvider.builder().automatic(true).build())))
    .build())) {
   String db="review_tbox_"+java.util.UUID.randomUUID().toString().replace("-","");
   var ds=(dev.morphia.MorphiaDatastore)Morphia.createDatastore(client,new dev.morphia.config.ManualMorphiaConfig().database(db)); ds.getMapper().map(TenantOntologyTBox.class);
   var dd=new DataDomain("org","account","tenant",0,"user");
   try {
    for(String hash:new String[]{"a","b"}) {var t=new TenantOntologyTBox();t.setTboxHash(hash);t.setRefName(hash);t.setDataDomain(dd);t.setActive(false);var content=new TenantOntologyTBox.ClassDefData();content.setName("Person");content.setLabel("Preserved label");t.setClasses(java.util.Map.of("Person",content));ds.save(t);}
    var repo=new Repo(ds); repo.setActiveTBox(dd,"a"); repo.failSave=true;
    assertThrows(IllegalStateException.class,()->repo.setActiveTBox(dd,"b"));
    assertEquals("a",repo.findActiveTBox(dd).orElseThrow().getTboxHash());
    assertEquals("Preserved label",repo.findActiveTBox(dd).orElseThrow().getClasses().get("Person").getLabel());
    repo.failSave=false; repo.setActiveTBox(dd,null);
    ds.getDatabase().getCollection("ontology_activation_guards").deleteMany(new org.bson.Document());
    var start=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
    try {
     var tasks=new java.util.ArrayList<Future<Boolean>>();
     for(String hash:new String[]{"a","b"}) tasks.add(pool.submit(()->{start.await();try{repo.setActiveTBox(dd,hash);return true;}catch(com.mongodb.MongoException conflict){return false;}}));
     start.countDown(); boolean succeeded=false;
     for(var task:tasks)succeeded |= task.get(20,TimeUnit.SECONDS);
     assertTrue(succeeded);
     assertEquals(1,ds.find(TenantOntologyTBox.class).filter(dev.morphia.query.filters.Filters.eq("active",true)).count());
    } finally {pool.shutdownNow();}
   } finally {client.getDatabase(db).drop();}
  }
 }
}
