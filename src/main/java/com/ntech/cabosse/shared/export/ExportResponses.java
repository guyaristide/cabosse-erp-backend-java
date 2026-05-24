package com.ntech.cabosse.shared.export;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Helpers pour construire une {@link Response} d'export streamée. Pose
 * les en-têtes {@code Content-Type} / {@code Content-Disposition} avec
 * un nom de fichier daté.
 */
public final class ExportResponses {

    private ExportResponses() {}

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Construit la {@link Response} : déclenche l'écriture du dataset
     * au moment du flush HTTP — pas de buffering intermédiaire.
     *
     * @param baseName  base du nom de fichier sans extension (ex. {@code "articles"})
     * @param format    {@link ExportFormat#CSV}, {@code XLSX} ou {@code PDF}
     * @param dataset   colonnes + lignes à sérialiser
     */
    public static <T> Response build(String baseName, ExportFormat format, ExportDataset<T> dataset) {
        String filename = baseName + "-" + ISO.format(LocalDate.now()) + "." + format.extension();
        StreamingOutput stream = out -> Exporters.write(format, dataset, out);
        return Response.ok(stream)
                .type(format.mimeType())
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .header("Cache-Control", "no-store")
                .build();
    }
}
