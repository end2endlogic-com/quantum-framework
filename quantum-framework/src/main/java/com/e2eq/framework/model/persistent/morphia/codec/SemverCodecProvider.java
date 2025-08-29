package com.e2eq.framework.model.persistent.morphia.codec;

import org.bson.codecs.Codec;
import org.bson.codecs.configuration.CodecProvider;
import org.bson.codecs.configuration.CodecRegistry;
import org.semver4j.Semver;

public class SemverCodecProvider implements CodecProvider {
    @Override
    public <T> Codec<T> get(Class<T> clazz, CodecRegistry registry) {
        if (Semver.class.isAssignableFrom(clazz)) {
            return (Codec<T>) new SemverCodec();
        }
        else
            return null;
    }
}
