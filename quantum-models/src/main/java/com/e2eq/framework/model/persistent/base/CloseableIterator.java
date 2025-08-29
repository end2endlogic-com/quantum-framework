package com.e2eq.framework.model.persistent.base;

import java.util.Iterator;

public interface CloseableIterator<T> extends Iterator<T>, AutoCloseable {
    /**
     * Closes the iterator and releases any system resources associated with it.
     * This method should be called when the iterator is no longer needed.
     */
    @Override
    void close();
}