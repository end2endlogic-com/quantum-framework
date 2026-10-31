package com.e2eq.framework.bootstrap.runtime;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;

import java.lang.annotation.Annotation;
import java.util.Iterator;

/**
 * Tiny adapter used by focused unit tests so CDI-backed registries can be
 * exercised without a container.
 */
final class SimpleIterableInstance<T> implements Instance<T> {

    private final Iterable<T> values;

    SimpleIterableInstance(Iterable<T> values) {
        this.values = values;
    }

    @Override
    public Instance<T> select(Annotation... qualifiers) {
        throw new UnsupportedOperationException("select is not implemented for test-only instance");
    }

    @Override
    public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
        throw new UnsupportedOperationException("select is not implemented for test-only instance");
    }

    @Override
    public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
        throw new UnsupportedOperationException("select is not implemented for test-only instance");
    }

    @Override
    public boolean isUnsatisfied() {
        return !iterator().hasNext();
    }

    @Override
    public boolean isAmbiguous() {
        return false;
    }

    @Override
    public void destroy(T instance) {
    }

    @Override
    public Instance.Handle<T> getHandle() {
        throw new UnsupportedOperationException("getHandle is not implemented for test-only instance");
    }

    @Override
    public Iterable<Instance.Handle<T>> handles() {
        throw new UnsupportedOperationException("handles is not implemented for test-only instance");
    }

    @Override
    public T get() {
        Iterator<T> iterator = iterator();
        if (!iterator.hasNext()) {
            throw new IllegalStateException("No values present");
        }
        return iterator.next();
    }

    @Override
    public Iterator<T> iterator() {
        return values.iterator();
    }
}
