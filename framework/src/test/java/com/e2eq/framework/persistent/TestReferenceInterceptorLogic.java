package com.e2eq.framework.persistent;

import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.base.ReferenceEntry;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStore;
import com.e2eq.framework.test.TestChildModel;
import com.e2eq.framework.test.TestParentModel;
import com.e2eq.framework.util.TestUtils;
import dev.morphia.mapping.Mapper;
import dev.morphia.mapping.codec.pojo.EntityModel;
import dev.morphia.mapping.codec.pojo.PropertyModel;
import dev.morphia.transactions.MorphiaSession;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Optional;

@QuarkusTest
public class TestReferenceInterceptorLogic {


    @Inject
    protected MorphiaDataStore dataStore;

    @Inject
    protected TestChildRepo childRepo;

    @Inject
    protected TestParentRepo parentRepo;


    TestParentModel getOrCreateParent() {
        Optional<TestParentModel> oparent = parentRepo.findByRefName("Test1");
        if (oparent.isPresent()) {
            return oparent.get();
        }

        TestParentModel parent = new TestParentModel();
        parent.setRefName("Test1");
        parent.setDataDomain(TestUtils.dataDomain);
        parent.setAuditInfo(TestUtils.createAuditInfo());
        parent = parentRepo.save(parent);

        return parent;
    }

    TestChildModel getOrCreateChild(TestParentModel parent) {
        Optional<TestChildModel> ochild = childRepo.findByRefName("Test1");
        if (ochild.isPresent()) {
            return ochild.get();
        }

        TestChildModel child = new TestChildModel();
        child.setRefName("Test1");
        child.setDataDomain(TestUtils.dataDomain);
        child.setAuditInfo(TestUtils.createAuditInfo());
        child.setParent(parent);
        child = childRepo.save(child);

        return child;
    }


    //@Test
    public void testLogic() {

        TestParentModel parent = getOrCreateParent();
        TestChildModel child = getOrCreateChild(parent);
        Mapper mapper = dataStore.getDefaultSystemDataStore().getMapper();
        // Get the mapped class information
        EntityModel mappedClass = mapper.getEntityModel(child.getClass());

        for (PropertyModel mappedField : mappedClass.getProperties()) {
            if (mappedField.isReference() ) {
                //Reference ref = mappedField.getAnnotation(Reference.class);
                if (BaseModel.class.isAssignableFrom(mappedField.getEntityModel().getType())) {
                    if (mappedField.getAccessor().get(child) != null){
                        BaseModel baseModel = (BaseModel) mappedField.getAccessor().get(child);
                        ReferenceEntry entry = new ReferenceEntry(child.getId(), mappedField.getEntityModel().getType().getTypeName());
                        baseModel.getReferences().add(entry);
                        // Update the bloom filter if necessary
                        // calculateBloomFilter(baseModel.getReferences());
                        dataStore.getDefaultSystemDataStore().save(mappedField.getAccessor().get(child));
                    }
                }
            }
        }

        Optional<TestParentModel> op = parentRepo.findByRefName("Test1");
        Assertions.assertTrue(op.isPresent());
        Assertions.assertTrue(op.get().getReferences().contains(new ReferenceEntry(child.getId(), TestChildModel.class.getName())));
        try {
            parentRepo.delete(op.get());
            Assertions.assertTrue(false); // should throw because there is a referencing child
        } catch (IllegalStateException e) {
            // expected
        }

        try (MorphiaSession s= dataStore.getDefaultSystemDataStore().startSession()) {
            s.startTransaction();
            s.delete(child);
            s.delete(parent);
            s.commitTransaction();
        } catch (Throwable t ) {
            Assertions.assertTrue(false);
        }

    }


    @Test
    public void testInterceptor() {
        TestParentModel parent = getOrCreateParent();
        TestChildModel child = getOrCreateChild(parent);

        Assertions.assertTrue(parent.getReferences().contains(new ReferenceEntry(child.getId(),TestChildModel.class.getTypeName())));

        try {
            parentRepo.delete(parent);
            Assertions.assertTrue(false); // should throw because there is a referencing child
        } catch (IllegalStateException e) {
            // expected
        }

        try (MorphiaSession s= dataStore.getDefaultSystemDataStore().startSession()) {
            s.startTransaction();
            s.delete(child);
            s.delete(parent);
            s.commitTransaction();
        } catch (Throwable t ) {
            Assertions.assertTrue(false);
        }



    }

}
