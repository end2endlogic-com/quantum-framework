package com.e2eq.framework.model.persistent.mongodb;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.bson.types.ObjectId;

import java.io.IOException;

public class ObjectIdJsonSerializer extends JsonSerializer<ObjectId> {
    @Override
    public void serialize(ObjectId o, JsonGenerator j, SerializerProvider s) throws IOException, JsonProcessingException {
        if(o == null) {
            j.writeNull();
        } else {
            j.writeString(o.toString());
        }
    }
}
