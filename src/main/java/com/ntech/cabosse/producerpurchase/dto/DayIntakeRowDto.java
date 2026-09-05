package com.ntech.cabosse.producerpurchase.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Une ligne de la fiche de stock des entrées du jour (CE-185) : un reçu,
 * avec les cumuls progressifs tels que le magasinier les tient sur le
 * carnet, quantité en stock et sacs du jour.
 */
public record DayIntakeRowDto(
        UUID purchaseId,
        LocalDate date,
        /** L'apporteur du carnet : le délégué qui a livré, sinon le producteur. */
        String supplierName,
        String ref,
        String deliveryRef,
        /** DELEGATE ou PRODUCER : le statut de la colonne du carnet. */
        String supplierKind,
        Integer nbSacs,
        BigDecimal weightKg,
        BigDecimal unitPrice,
        BigDecimal amount,
        /** Stock cumulé après cette entrée : ouverture + entrées du jour. */
        BigDecimal cumulativeQuantity,
        /** Sacs cumulés du jour. Le stock ne compte pas les sacs d'ouverture. */
        Integer cumulativeBags
) {}
