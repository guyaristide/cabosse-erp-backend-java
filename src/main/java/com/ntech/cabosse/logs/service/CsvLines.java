package com.ntech.cabosse.logs.service;

import com.ntech.cabosse.shared.export.Exporters;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Écrit un CSV ligne à ligne dans un flux déjà ouvert.
 *
 * <p>{@code Exporters.writeCsv} veut toutes les lignes en mémoire ; deux
 * ans de traces ne tiennent pas dans une liste. Même forme de fichier que
 * les exports (BOM UTF-8, virgule, retour Windows) et même neutralisation
 * des cellules qu'un tableur lirait comme une formule.</p>
 */
final class CsvLines {

    private final OutputStream out;
    private final StringBuilder line = new StringBuilder();
    private long rows;

    CsvLines(OutputStream out, List<String> headers) throws IOException {
        this.out = out;
        out.write(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF });
        row(headers.toArray());
        rows = 0;
    }

    void row(Object... cells) throws IOException {
        line.setLength(0);
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) line.append(',');
            line.append(Exporters.csvCell(text(cells[i])));
        }
        line.append("\r\n");
        out.write(line.toString().getBytes(StandardCharsets.UTF_8));
        rows++;
    }

    /** Lignes écrites, en-tête exclu. */
    long rows() {
        return rows;
    }

    private static String text(Object v) {
        if (v == null) return "";
        if (v instanceof Date d) return d.toInstant().toString();
        if (v instanceof Instant i) return i.toString();
        return String.valueOf(v);
    }
}
