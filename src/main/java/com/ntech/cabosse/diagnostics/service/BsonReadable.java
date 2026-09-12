package com.ntech.cabosse.diagnostics.service;

import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rend un document Mongo lisible par un humain.
 *
 * <p>L'API n'expose que des DTO, règle de la maison. Le diagnostic
 * plateforme est l'exception assumée : son objet est précisément de
 * montrer ce que la base contient réellement, y compris un champ qu'aucun
 * DTO ne porte. Sans quoi on ne peut pas répondre à « ce reçu porte-t-il
 * un délégué ? », qui est la question qui coûte des heures.</p>
 *
 * <p>Jackson, laissé seul face au BSON, rend {@code Decimal128} comme un
 * objet à six booléens et un {@code ObjectId} comme trois entiers. Le
 * mode Extended JSON du pilote, lui, rend chaque UUID en binaire encodé,
 * et nos documents n'ont presque que des UUID. D'où cette traduction,
 * type par type, vers ce qu'un lecteur attend.</p>
 */
public final class BsonReadable {

    private BsonReadable() {
    }

    /** Le document entier, prêt à être sérialisé en JSON. */
    public static Map<String, Object> of(Document document) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            out.put(entry.getKey(), value(entry.getValue()));
        }
        return out;
    }

    private static Object value(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Document nested) return of(nested);
        if (raw instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) out.add(value(item));
            return out;
        }
        // Les montants : un Decimal128 se lit comme le nombre qu'il est.
        if (raw instanceof Decimal128 decimal) return decimal.bigDecimalValue().toPlainString();
        if (raw instanceof ObjectId oid) return oid.toHexString();
        if (raw instanceof Date date) return date.toInstant().toString();
        // Les identifiants stockés en binaire de sous-type 4 : c'est un
        // UUID, et c'est sous cette forme qu'on le compare à l'écran.
        if (raw instanceof Binary binary) return readableBinary(binary);
        return raw;
    }

    private static Object readableBinary(Binary binary) {
        byte[] data = binary.getData();
        if (binary.getType() == 4 && data.length == 16) {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            return new UUID(buffer.getLong(), buffer.getLong()).toString();
        }
        return "binaire (" + data.length + " octets)";
    }
}
