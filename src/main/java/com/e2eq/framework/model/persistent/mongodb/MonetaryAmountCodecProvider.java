package com.e2eq.framework.model.persistent.mongodb;

import org.bson.codecs.Codec;
import org.bson.codecs.configuration.CodecProvider;
import org.bson.codecs.configuration.CodecRegistry;

import javax.money.MonetaryAmount;

public class MonetaryAmountCodecProvider implements CodecProvider {

    @Override
    public <T> Codec<T> get(Class<T> clazz, CodecRegistry registry) {
        if (clazz.equals(MonetaryAmount.class)) {
            return (Codec<T>) new MonetaryAmountCodec();
        }
        return null;
    }
}
