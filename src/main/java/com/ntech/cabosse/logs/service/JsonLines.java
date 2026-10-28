package com.ntech.cabosse.logs.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.Decimal128;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Écrit un document par ligne, en JSON lisible.
 *
 * <p>Le JSON du pilote Mongo écrit un UUID en binaire encodé et une date
 * en objet {@code $date} : exact, mais illisible pour qui ouvre l'archive
 * sans outil. Ici les identifiants sortent en texte, les dates en ISO-8601
 * et les montants en nombres, ce que n'importe quel lecteur comprend.</p>
 */
final class JsonLines {

    private final OutputStream out;
    private final ObjectMapper json;
    private long rows;

    JsonLines(OutputStream out, ObjectMapper json) {
        this.out = out;
        this.json = json;
    }

    void row(Document document) throws IOException {
        out.write(json.writeValueAsBytes(readable(document)));
        out.write('\n');
        rows++;
    }

    long rows() {
        return rows;
    }

    @SuppressWarnings("unchecked")
    static Object readable(Object value) {
        if (value == null) return null;
        if (value instanceof UUID u) return u.toString();
        if (value instanceof Date d) return d.toInstant().toString();
        if (value instanceof Decimal128 d) return d.bigDecimalValue();
        if (value instanceof Binary b) return Base64.getEncoder().encodeToString(b.getData());
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<Object, Object>) map).entrySet()) {
                copy.put(String.valueOf(e.getKey()), readable(e.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object o : list) copy.add(readable(o));
            return copy;
        }
        return value;
    }
}
