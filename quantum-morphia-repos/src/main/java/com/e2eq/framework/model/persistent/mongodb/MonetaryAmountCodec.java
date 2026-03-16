package com.e2eq.framework.model.persistent.mongodb;

import com.mongodb.MongoClientSettings;
import org.bson.BsonReader;
import org.bson.BsonWriter;
import org.bson.Document;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.types.Decimal128;
import org.javamoney.moneta.Money;

import javax.money.MonetaryAmount;
import java.math.BigDecimal;

public class MonetaryAmountCodec implements Codec<MonetaryAmount> {

    private final Codec<Document> documentCodec;

    public MonetaryAmountCodec() {
        documentCodec = MongoClientSettings.getDefaultCodecRegistry().get(Document.class);
    }
    @Override
    public MonetaryAmount decode(BsonReader reader, DecoderContext decoderContext) {
        Document doc = documentCodec.decode(reader, decoderContext);
        Object amountObj = doc.get("amount");
        String currency = doc.getString("currency");
        BigDecimal bd;
        if (amountObj instanceof Decimal128) {
            bd = ((Decimal128) amountObj).bigDecimalValue();
        } else if (amountObj instanceof Number) {
            bd = BigDecimal.valueOf(((Number) amountObj).doubleValue());
        } else {
            bd = BigDecimal.ZERO;
        }
        return Money.of(bd, currency);
    }

    @Override
    public void encode(BsonWriter writer, MonetaryAmount value, EncoderContext encoderContext) {
        if (value != null ) {
            BigDecimal bd = value.getNumber().numberValue(BigDecimal.class);
            if (bd == null) {
                bd = BigDecimal.valueOf(value.getNumber().doubleValue());
            }
            Decimal128 d = new Decimal128(bd);
            Document doc = new Document();
            doc.put("amount", d);
            doc.put("currency", value.getCurrency().getCurrencyCode());
            documentCodec.encode(writer, doc, encoderContext);
        }

    }

    @Override
    public Class<MonetaryAmount> getEncoderClass() {
        return MonetaryAmount.class;
    }
}
