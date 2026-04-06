package com.e2eq.framework.actionenablement.runtime.resolver;

import com.e2eq.framework.actionenablement.model.DependencyCheckRef;
import com.e2eq.framework.actionenablement.model.EnablementBlocker;
import com.e2eq.framework.actionenablement.model.EnablementImpact;
import com.e2eq.framework.actionenablement.runtime.DependencyResolutionResult;
import com.e2eq.framework.actionenablement.runtime.EnablementEvaluationContext;
import com.e2eq.framework.actionenablement.spi.ActionDependencyResolver;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import dev.morphia.mapping.codec.pojo.EntityModel;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

@ApplicationScoped
public class EntityExistsActionDependencyResolver implements ActionDependencyResolver {

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    @Override
    public String supportsType() {
        return "entity-exists";
    }

    @Override
    public DependencyResolutionResult evaluate(DependencyCheckRef dependency, EnablementEvaluationContext context) {
        String refName = dependency.getRefName() == null ? "" : dependency.getRefName().trim();
        String modelClassOrEntityName = stringConfig(dependency, "modelClass");
        if (refName.isEmpty() || modelClassOrEntityName.isEmpty()) {
            return DependencyResolutionResult.blocked(EnablementBlocker.builder()
                    .impact(EnablementImpact.READY)
                    .type("entity-exists")
                    .code("entity-dependency-invalid")
                    .message("Entity dependency must include both refName and config.modelClass.")
                    .severity("error")
                    .metadata(Map.of("refName", refName, "modelClass", modelClassOrEntityName))
                    .build());
        }

        Class<? extends UnversionedBaseModel> modelClass = resolveModelClass(modelClassOrEntityName, context.getRealm());
        if (modelClass == null) {
            return DependencyResolutionResult.blocked(EnablementBlocker.builder()
                    .impact(EnablementImpact.READY)
                    .type("entity-exists")
                    .code("entity-model-unresolved")
                    .message("Could not resolve entity model '" + modelClassOrEntityName + "'.")
                    .severity("error")
                    .metadata(Map.of("modelClass", modelClassOrEntityName, "realm", context.getRealm()))
                    .build());
        }

        UnversionedBaseModel existing = morphiaDataStoreWrapper.getDataStore(context.getRealm())
                .find(modelClass)
                .filter(Filters.eq("refName", refName))
                .first();

        if (existing != null) {
            return DependencyResolutionResult.satisfied();
        }

        return DependencyResolutionResult.blocked(EnablementBlocker.builder()
                .impact(EnablementImpact.READY)
                .type("entity-exists")
                .code("entity-missing")
                .message("Required entity '" + refName + "' was not found for model '" + modelClass.getSimpleName() + "'.")
                .severity("error")
                .metadata(Map.of("refName", refName, "modelClass", modelClass.getName(), "realm", context.getRealm()))
                .build());
    }

    private String stringConfig(DependencyCheckRef dependency, String key) {
        Object value = dependency.getConfig() == null ? null : dependency.getConfig().get(key);
        return value instanceof String stringValue ? stringValue.trim() : "";
    }

    @SuppressWarnings("unchecked")
    private Class<? extends UnversionedBaseModel> resolveModelClass(String modelClassOrEntityName, String realm) {
        try {
            Class<?> loaded = Class.forName(modelClassOrEntityName);
            if (UnversionedBaseModel.class.isAssignableFrom(loaded)) {
                return (Class<? extends UnversionedBaseModel>) loaded;
            }
        }
        catch (ClassNotFoundException ignored) {
        }

        for (EntityModel entityModel : morphiaDataStoreWrapper.getDataStore(realm).getMapper().getMappedEntities()) {
            Class<?> type = entityModel.getType();
            if (!UnversionedBaseModel.class.isAssignableFrom(type)) {
                continue;
            }
            if (type.getName().equalsIgnoreCase(modelClassOrEntityName)
                    || type.getSimpleName().equalsIgnoreCase(modelClassOrEntityName)
                    || entityModel.getName().equalsIgnoreCase(modelClassOrEntityName)
                    || entityModel.collectionName().equalsIgnoreCase(modelClassOrEntityName)) {
                return (Class<? extends UnversionedBaseModel>) type;
            }
        }

        return null;
    }
}
