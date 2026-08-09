package com.ntech.cabosse.shared.persistence;

import org.bson.BsonInvalidOperationException;
import org.bson.BsonReader;
import org.bson.BsonType;
import org.bson.BsonWriter;
import org.bson.codecs.BigDecimalCodec;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecProvider;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.types.Decimal128;
import jakarta.inject.Singleton;

import java.math.BigDecimal;

/**
 * Lecture tolérante des montants, écriture toujours en Decimal128.
 *
 * <p>Le codec du pilote exige un Decimal128 en lecture et échoue sur un
 * entier. Or un montant n'arrive pas toujours par le modèle : une
 * migration qui écrit {@code 0}, un script d'exploitation, un pipeline
 * d'agrégation dont les deux opérandes sont absents produisent un Int32.
 * Le document devient alors illisible et c'est la liste entière qui tombe,
 * pas la ligne fautive.</p>
 *
 * <p>Refuser ces données n'a aucun bénéfice : un zéro entier vaut zéro. On
 * les lit donc, et on réécrit en Decimal128 pour que la valeur se
 * normalise d'elle-même à la première mise à jour.</p>
 */
@Singleton
public class TolerantBigDecimalCodec implements Codec<BigDecimal>, CodecProvider {

    private static final BigDecimalCodec DELEGATE = new BigDecimalCodec();

    @Override
    public BigDecimal decode(BsonReader reader, DecoderContext context) {
        BsonType type = reader.getCurrentBsonType();
        return switch (type) {
            case DECIMAL128 -> reader.readDecimal128().bigDecimalValue();
            case INT32 -> BigDecimal.valueOf(reader.readInt32());
            case INT64 -> BigDecimal.valueOf(reader.readInt64());
            case DOUBLE -> BigDecimal.valueOf(reader.readDouble());
            // Un montant transité par un export texte reste un montant.
            case STRING -> parse(reader.readString());
            case NULL -> {
                reader.readNull();
                yield null;
            }
            default -> throw new BsonInvalidOperationException(
                    "Montant illisible : type BSON " + type + " inattendu.");
        };
    }

    @Override
    public void encode(BsonWriter writer, BigDecimal value, EncoderContext context) {
        if (value == null) {
            writer.writeNull();
            return;
        }
        writer.writeDecimal128(new Decimal128(value));
    }

    @Override
    public Class<BigDecimal> getEncoderClass() {
        return BigDecimal.class;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Codec<T> get(Class<T> clazz, CodecRegistry registry) {
        return clazz == BigDecimal.class ? (Codec<T>) this : null;
    }

    private static BigDecimal parse(String raw) {
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new BsonInvalidOperationException("Montant illisible : « " + raw + " ».", e);
        }
    }

    /** Conservé pour signaler que le codec du pilote reste la référence d'écriture. */
    static BigDecimalCodec delegate() {
        return DELEGATE;
    }
}
