package com.e2eq.framework.model.persistent.morphia.codec;

import io.quarkus.logging.Log;
import org.bson.BsonReader;
import org.bson.BsonWriter;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.jboss.logging.Logger;
import org.semver4j.Semver;

public class SemverCodec implements Codec <Semver>{
    @Override
    public Semver decode(BsonReader reader, DecoderContext decoderContext) {
        Semver rc = null;
        reader.readStartDocument();
        String fieldName;
        while(reader.readBsonType() != org.bson.BsonType.END_OF_DOCUMENT) {
            fieldName = reader.readName();
            switch (fieldName) {
                case "version":
                    String versionString = reader.readString();
                    rc = new Semver(versionString);
                    break;
                default:
                    reader.skipValue();
                    break;
            }
        }
        reader.readEndDocument();
        if (Log.isEnabled(Logger.Level.WARN)) {
            if (rc == null) {
                Log.warn("SemverCodec: unable to decode semver, version string not found in document");
            }
        }
        return rc;
    }

    @Override
    public void encode(BsonWriter writer, Semver value, EncoderContext encoderContext) {
        writer.writeStartDocument();
        writer.writeString("version", value.getVersion());
        writer.writeInt32("major", value.getMajor());
        writer.writeInt32("minor", value.getMinor());
        writer.writeInt32("patch", value.getPatch());
        if (value.getPreRelease() != null) {
            writer.writeStartArray("preRelease");
            for (String preRelease : value.getPreRelease()) {
                writer.writeString(preRelease);
            }
            writer.writeEndArray();
        }
        writer.writeBoolean("stable", value.isStable());
        if (value.getBuild() != null) {
            writer.writeStartArray("build");
            for (String build : value.getBuild()) {
                writer.writeString(build);
            }
            writer.writeEndArray();
        }

        writer.writeEndDocument();

    }

    @Override
    public Class<Semver> getEncoderClass() {
        return Semver.class;
    }
}
