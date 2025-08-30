package com.e2eq.framework.persistent;

import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStore;
import com.e2eq.framework.model.persistent.morphia.MorphiaUtils;
import com.e2eq.framework.securityrules.RuleContext;
import com.e2eq.framework.securityrules.SecuritySession;
import com.e2eq.framework.test.BookModel;
import dev.morphia.annotations.Reference;
import dev.morphia.query.filters.Filter;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;

@QuarkusTest
public class TestReferenceQuery extends BaseRepoTest{
    @Inject
    RuleContext ruleContext;

    @Inject
    MorphiaDataStore dataStore;


    @Test
    public void testQuery() throws NoSuchFieldException, NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException {

        try (final SecuritySession s = new SecuritySession(pContext, rContext)) {

            Class<?> clazz = BookModel.class;
            Field field = clazz.getDeclaredField("author");
            field.setAccessible(true);
            Annotation annotation = field.getAnnotation(Reference.class);
            if (annotation!= null) {
                Object object = field.getType().getDeclaredConstructor().newInstance();
                if (object instanceof BaseModel) {
                    ((BaseModel) object).setId(new ObjectId());
                }

            }

            Filter x = MorphiaUtils.convertToFilter("author:@@" + new ObjectId().toString(), BookModel.class);
            Log.info("Filter: " + x.toString());
        }
    }
}
