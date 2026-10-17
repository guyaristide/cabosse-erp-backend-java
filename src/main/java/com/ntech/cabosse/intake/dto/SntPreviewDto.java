package com.ntech.cabosse.intake.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Prévisualisation de la comptabilisation d'un bordereau : ce que
 * chaque ligne du fichier de traçabilité deviendra, avant de valider.
 */
@Schema(description = "Prévisualisation de comptabilisation d'un bordereau")
public record SntPreviewDto(
        int totalRows,
        int readyRows,
        int warningRows,
        int invalidRows,
        /** Producteurs absents du registre, qui seront créés au passage. */
        int membersToCreate,
        BigDecimal totalWeightKg,
        BigDecimal totalAmount,
        BigDecimal noteNetWeightKg,
        /** Poids net du bordereau moins la somme du fichier. */
        BigDecimal weightGapKg,
        /** Délégué dont les avances s'apureront, s'il a été reconnu. */
        UUID delegateSupplierId,
        String delegateName,
        /**
         * Un fournisseur est nommé (bordereau ou fichier) mais
         * introuvable au référentiel.
         *
         * <p>Avertissement, non refus : la coopérative achète aussi à
         * des producteurs, membres ou non, et le reçu se pose sur celui
         * que le fichier nomme. Seule une ligne qui ne nomme personne et
         * n'a aucun fournisseur reconnu est refusée (expert,
         * 13/10/2026).</p>
         */
        boolean supplierUnmatched,
        /**
         * Fournisseur du bordereau, délégué ou non : il porte les reçus
         * dont le document ne nomme pas le producteur.
         */
        UUID supplierId,
        String supplierName,
        /** Lignes qui reposeront sur le fournisseur faute de producteur nommé. */
        int rowsOnSupplier,
        List<Row> rows
) {
    public record Row(
            int rowNumber,
            /** READY, WARNING ou INVALID. */
            String status,
            List<String> issues,
            String reference,
            LocalDate date,
            String producerName,
            String producerPhone,
            UUID memberId,
            String memberName,
            boolean memberToCreate,
            /** Sacs pesés sur la livraison, quand le fichier les porte. */
            Integer nbSacs,
            BigDecimal weightKg,
            BigDecimal amount,
            /** Prix au kilo déduit : montant divisé par poids. */
            BigDecimal pricePerKg,
            String paymentMethod,
            /**
             * Le statut écrit dans le fichier, quand il en porte un.
             * Nommé ainsi pour ne pas se confondre avec {@code status},
             * qui dit si la ligne passe.
             */
            String statusLabel
    ) {}
}
