package com.e2eq.framework.rest.resources;

import com.e2eq.framework.model.persistent.base.StaticDynamicList;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import com.e2eq.framework.model.persistent.morphia.ObjectListRepo;
import com.e2eq.framework.model.persistent.morphia.StaticDynamicListRepo;

public abstract class StaticDynamicListResource<
        O extends UnversionedBaseModel,
        OR extends MorphiaRepo<O>,
        T extends StaticDynamicList<O>,
        TR extends ObjectListRepo<O,T, OR>> extends BaseResource<T,TR>{
    protected StaticDynamicListResource(TR repo) {
        super(repo);
    }
}
