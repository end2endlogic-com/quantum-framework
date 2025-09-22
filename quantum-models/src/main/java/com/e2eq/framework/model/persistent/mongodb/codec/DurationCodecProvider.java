package com.e2eq.framework.model.persistent.mongodb.codec;

import org.bson.codecs.Codec;
import org.bson.codecs.configuration.CodecProvider;
import org.bson.codecs.configuration.CodecRegistry;

import java.time.Duration;

public class DurationCodecProvider implements CodecProvider {

    private static final DurationCodec DURATION_CODEC = new DurationCodec();

    @SuppressWarnings("unchecked")
    @Override
    public <T> Codec<T> get(Class<T> clazz, CodecRegistry registry) {
        if (clazz == Duration.class) {
            return (Codec<T>) DURATION_CODEC;
        }
        return null;
    }
}
