package com.e2eq.framework.securityrules;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import jakarta.enterprise.context.ApplicationScoped;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

@ApplicationScoped
public class DefaultLabelResolver implements LabelResolver {
    @Override
    public boolean supports(Class<?> type) {
        return UnversionedBaseModel.class.isAssignableFrom(type);
    }

    @Override
    public Set<String> resolveLabels(Object entity) {
        Set<String> labels = new LinkedHashSet<>();
        if (!(entity instanceof UnversionedBaseModel m)) return labels;

        // tags (String[])
        try {
            String[] tags = m.getTags();
            if (tags != null) {
                for (String t : tags) if (t != null) labels.add(t);
            }
        } catch (Throwable ignored) {}

        // advancedTags (collection with getName())
        try {
            var adv = m.getAdvancedTags();
            if (adv != null) {
                adv.forEach(t -> {
                    try {
                        if (t != null) {
                            Object nameObj = t.getClass().getMethod("getName").invoke(t);
                            if (nameObj instanceof String name && name != null) labels.add(name);
                        }
                    } catch (Throwable ignored2) {}
                });
            }
        } catch (Throwable ignored) {}

        // optional: dynamicAttributes field with elements exposing getLabel() and isInheritable()
        try {
            Field f = entity.getClass().getDeclaredField("dynamicAttributes");
            f.setAccessible(true);
            Object val = f.get(entity);
            if (val instanceof Collection<?> attrs) {
                for (Object da : attrs) {
                    try {
                        Object lbl = da.getClass().getMethod("getLabel").invoke(da);
                        Object inh = da.getClass().getMethod("isInheritable").invoke(da);
                        if (lbl instanceof String s && s != null) {
                            if (!(inh instanceof Boolean b) || Boolean.TRUE.equals(b)) {
                                labels.add(s);
                            }
                        }
                    } catch (Throwable ignored2) {}
                }
            }
        } catch (NoSuchFieldException ignore) {
            // best-effort only
        } catch (Throwable ignored) {}

        return labels;
    }
}
