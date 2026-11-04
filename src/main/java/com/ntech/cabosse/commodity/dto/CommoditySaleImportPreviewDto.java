package com.ntech.cabosse.commodity.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Prévisualisation d'un import de ventes cacao (backlog NEG-02). */
@Schema(description = "Prévisualisation import ventes cacao")
public record CommoditySaleImportPreviewDto(
        int totalRows,
        int readyRows,
        int invalidRows,
        List<Row> rows
) {
    /**
     * {@code ATTACHED} : la ligne n'est pas une vente mais un chargement de
     * plus sur la vente d'une autre ligne (vente multisites, VTE-16). Elle
     * ne compte ni prête ni refusée : elle part avec sa vente.
     */
    public enum Status { READY, INVALID, ATTACHED }

    /**
     * Une ligne, ce qui l'empêche, et ce qui se fera pour elle.
     *
     * <p>{@code issues} bloque, {@code notices} prévient. Une valeur
     * absente du référentiel sera créée : l'annoncer avant la validation
     * vaut mieux que de la créer en silence, ou que de refuser un fichier
     * pour un grade qu'il suffisait d'ouvrir.</p>
     */
    public record Row(int rowNumber, Status status, Normalized normalized,
                      List<FieldIssue> issues, List<FieldIssue> notices) {

        /** Ligne sans avis, pour les appelants qui n'en produisent pas. */
        public Row(int rowNumber, Status status, Normalized normalized, List<FieldIssue> issues) {
            this(rowNumber, status, normalized, issues, List.of());
        }
    }

    /**
     * La ligne telle que l'import la lira. Quand un bordereau de sortie
     * est reconnu, {@code dispatchNoteRefs} le nomme (tous, pour une vente
     * à plusieurs chargements), {@code dispatchedKg} et
     * {@code dispatchedBags} disent ce qu'il a fait sortir : c'est ce poids
     * qui devient le poids déclaré de la vente, pas celui du fichier.
     */
    public record Normalized(
            UUID customerId,
            String customerName,
            UUID articleId,
            String articleLabel,
            String date,
            BigDecimal declaredKg,
            BigDecimal acceptedKg,
            BigDecimal amount,
            String dispatchNoteRefs,
            BigDecimal dispatchedKg,
            Integer dispatchedBags
    ) {
        public Normalized(UUID customerId, String customerName, UUID articleId, String articleLabel,
                          String date, BigDecimal declaredKg, BigDecimal acceptedKg, BigDecimal amount) {
            this(customerId, customerName, articleId, articleLabel, date, declaredKg, acceptedKg, amount,
                    null, null, null);
        }
    }

    public record FieldIssue(String field, String message) {}
}
