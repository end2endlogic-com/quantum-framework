package com.e2eq.framework.appregistration.runtime;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A CDI {@link Instance} over a fixed collection, so the factory can be
 * constructed and exercised without a container. Mirrors the adapter
 * bootstrap-core uses for the same reason.
 */
final class FixedInstance<T> implements Instance<T> {

    private final List<T> items;

    FixedInstance(Iterable<T> items) {
        List<T> copy = new ArrayList<>();
        if (items != null) {
            items.forEach(copy::add);
        }
        this.items = List.copyOf(copy);
    }

    @Override public Iterator<T> iterator() { return items.iterator(); }
    @Override public T get() { return items.get(0); }
    @Override public Instance<T> select(Annotation... qualifiers) { return this; }
    @Override public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
        throw new UnsupportedOperationException();
    }
    @Override public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
        throw new UnsupportedOperationException();
    }
    @Override public boolean isUnsatisfied() { return items.isEmpty(); }
    @Override public boolean isAmbiguous() { return items.size() > 1; }
    @Override public void destroy(T instance) { }
    @Override public Handle<T> getHandle() { throw new UnsupportedOperationException(); }
    @Override public Iterable<? extends Handle<T>> handles() { throw new UnsupportedOperationException(); }
}
