package com.e2eq.framework.model.persistent.mongodb;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.bson.codecs.configuration.CodecProvider;
import org.bson.codecs.jsr310.Jsr310CodecProvider;

@ApplicationScoped
public class MongoCodecConfig {

    @Produces
    public CodecProvider jsr310CodecProvider() {
        return new Jsr310CodecProvider();
    }
}
