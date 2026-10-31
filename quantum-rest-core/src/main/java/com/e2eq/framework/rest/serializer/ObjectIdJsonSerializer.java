package com.e2eq.framework.rest.serializer;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.bson.types.ObjectId;

import java.io.IOException;

public class ObjectIdJsonSerializer extends JsonSerializer<ObjectId> {
    @Override
    public void serialize(ObjectId objectId, JsonGenerator generator, SerializerProvider provider)
            throws IOException {
        if (objectId == null) {
            generator.writeNull();
        } else {
            generator.writeString(objectId.toHexString());
        }
    }
}
