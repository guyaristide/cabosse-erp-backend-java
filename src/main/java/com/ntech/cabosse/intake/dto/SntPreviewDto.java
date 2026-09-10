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
         * Un délégué est nommé (bordereau ou fichier) mais introuvable au
         * référentiel : la validation refusera, un reçu créé sans
         * rattachement n'apure jamais son compte d'avances.
         */
        boolean delegateUnmatched,
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
            BigDecimal weightKg,
            BigDecimal amount,
            /** Prix au kilo déduit : montant divisé par poids. */
            BigDecimal pricePerKg,
            String paymentMethod
    ) {}
}
