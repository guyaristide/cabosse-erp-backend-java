package com.ntech.cabosse.shared.export;

import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Helpers pour construire une {@link Response} d'export streamée. Pose
 * les en-têtes {@code Content-Type} / {@code Content-Disposition} avec
 * un nom de fichier daté.
 *
 * <p>Sélection de colonnes : si la requête porte un ou plusieurs
 * paramètres {@code columns} (libellés d'en-tête, répétés), le dataset
 * est restreint à ces colonnes, dans l'ordre du dataset. Lue ici plutôt
 * que dans chaque contrôleur : la vingtaine d'endpoints d'export en
 * bénéficie sans se répéter. Un libellé inconnu est ignoré ; une
 * sélection qui ne matche rien laisse le dataset entier, pour qu'un
 * front désynchronisé produise un export complet plutôt que vide.
 * Le pseudo-format {@link ExportFormat#META} renvoie la liste des
 * colonnes proposables et sert le sélecteur du front.</p>
 */
public final class ExportResponses {

    private ExportResponses() {}

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Construit la {@link Response} : déclenche l'écriture du dataset
     * au moment du flush HTTP — pas de buffering intermédiaire.
     *
     * @param baseName  base du nom de fichier sans extension (ex. {@code "articles"})
     * @param format    {@link ExportFormat#CSV}, {@code XLSX}, {@code PDF} ou {@code META}
     * @param dataset   colonnes + lignes à sérialiser
     */
    public static <T> Response build(String baseName, ExportFormat format, ExportDataset<T> dataset) {
        if (format == ExportFormat.META) {
            StreamingOutput meta = out -> Exporters.write(ExportFormat.META, dataset, out);
            return Response.ok(meta).type(format.mimeType()).header("Cache-Control", "no-store").build();
        }
        ExportDataset<T> effective = restrictToRequestedColumns(dataset);
        String filename = baseName + "-" + ISO.format(LocalDate.now()) + "." + format.extension();
        StreamingOutput stream = out -> Exporters.write(format, effective, out);
        return Response.ok(stream)
                .type(format.mimeType())
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .header("Cache-Control", "no-store")
                .build();
    }

    /** Dataset restreint aux colonnes demandées par la requête courante. */
    private static <T> ExportDataset<T> restrictToRequestedColumns(ExportDataset<T> dataset) {
        Set<String> wanted = requestedColumns();
        if (wanted.isEmpty()) return dataset;
        List<ExportColumn<T>> kept = dataset.columns().stream()
                .filter(c -> wanted.contains(c.header()))
                .toList();
        if (kept.isEmpty() || kept.size() == dataset.columns().size()) return dataset;
        return new ExportDataset<>(dataset.title(), kept, dataset.rows());
    }

    /** Paramètres {@code columns} de la requête HTTP courante, s'il y en a une. */
    private static Set<String> requestedColumns() {
        try {
            var current = CDI.current().select(CurrentVertxRequest.class).get().getCurrent();
            if (current == null) return Set.of();
            return new LinkedHashSet<>(current.request().params().getAll("columns"));
        } catch (RuntimeException e) {
            // Hors requête HTTP (test unitaire, batch) : pas de sélection.
            return Set.of();
        }
    }
}
