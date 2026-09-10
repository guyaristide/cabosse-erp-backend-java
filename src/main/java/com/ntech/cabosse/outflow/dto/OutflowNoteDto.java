package com.ntech.cabosse.outflow.dto;

import com.ntech.cabosse.outflow.entity.OutflowNoteEntity;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Bordereau de sortie du carnet, tel que l'écran le lit. Les
 * rapprochements sont calculés à la lecture : la réception par le N° BR,
 * la vente par le couple N° BS + N° chargement, quel que soit l'ordre
 * des imports.
 */
@Schema(description = "Bordereau de sortie du magasin")
public record OutflowNoteDto(
        UUID id,
        String ref,
        LocalDate date,
        String movement,
        String campaignLabel,
        UUID campaignId,
        String productLabel,
        String dispatchNoteNumber,
        String loadingNumber,
        String truckNumber,
        String destination,
        String customerCode,
        String customerName,
        UUID customerId,
        Integer lineNumber,
        BigDecimal grossWeightKg,
        Integer bagCount,
        BigDecimal netWeightKg,
        /** Le bordereau de réception du même numéro, s'il est connu. */
        UUID intakeNoteId,
        /** La vente qui porte ce N° BS et ce N° chargement, si elle existe. */
        UUID saleId,
        String saleRef,
        UUID siteId,
        Instant createdAt
) {
    public static OutflowNoteDto from(OutflowNoteEntity e, UUID intakeNoteId,
                                      UUID saleId, String saleRef) {
        return new OutflowNoteDto(
                e.id, e.ref, e.date, e.movement, e.campaignLabel, e.campaignId,
                e.productLabel, e.dispatchNoteNumber, e.loadingNumber,
                e.truckNumber, e.destination, e.customerCode, e.customerName,
                e.customerId, e.lineNumber,
                e.grossWeightKg, e.bagCount, e.netWeightKg,
                intakeNoteId, saleId, saleRef, e.siteId, e.createdAt);
    }
}
