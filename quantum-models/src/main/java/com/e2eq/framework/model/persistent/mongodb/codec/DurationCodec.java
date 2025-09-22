package com.e2eq.framework.model.persistent.mongodb.codec;

import org.bson.*;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;

import java.time.Duration;

public class DurationCodec implements Codec<Duration> {

    @Override
    public Duration decode(BsonReader reader, DecoderContext decoderContext) {
        BsonType type = reader.getCurrentBsonType();
        if (type == BsonType.NULL) {
            reader.readNull();
            return null;
        }
        if (type == BsonType.STRING) {
            String iso = reader.readString();
            return Duration.parse(iso);
        }
        if (type == BsonType.INT64) {
            long seconds = reader.readInt64();
            return Duration.ofSeconds(seconds);
        }
        if (type == BsonType.INT32) {
            int seconds = reader.readInt32();
            return Duration.ofSeconds(seconds);
        }
        // Fallback: try to read as document { seconds: <long>, nanos: <int> }
        if (type == BsonType.DOCUMENT) {
            reader.readStartDocument();
            Long seconds = null;
            Integer nanos = null;
            while (reader.readBsonType() != BsonType.END_OF_DOCUMENT) {
                String name = reader.readName();
                switch (name) {
                    case "seconds":
                        seconds = reader.readInt64();
                        break;
                    case "nanos":
                        nanos = reader.readInt32();
                        break;
                    default:
                        reader.skipValue();
                }
            }
            reader.readEndDocument();
            if (seconds == null) {
                return null;
            }
            return (nanos == null) ? Duration.ofSeconds(seconds) : Duration.ofSeconds(seconds, nanos);
        }
        throw new BsonInvalidOperationException("Unsupported BSON type for Duration: " + type);
    }

    @Override
    public void encode(BsonWriter writer, Duration value, EncoderContext encoderContext) {
        if (value == null) {
            writer.writeNull();
            return;
        }
        // Write ISO-8601 for readability and portability
        writer.writeString(value.toString());
    }

    @Override
    public Class<Duration> getEncoderClass() {
        return Duration.class;
    }
}
