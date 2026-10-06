package com.ntech.cabosse.producerpurchase.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Une ligne de la fiche de stock du jour (CE-185) : un reçu ou une
 * correction de magasin, avec les cumuls progressifs tels que le
 * magasinier les tient sur le carnet, quantité en stock et sacs du jour.
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
        /** Stock cumulé après cette ligne : ouverture, entrées, corrections. */
        BigDecimal cumulativeQuantity,
        /** Sacs cumulés du jour. Le stock ne compte pas les sacs d'ouverture. */
        Integer cumulativeBags,
        /**
         * {@code RECEIPT} ou {@code CORRECTION}.
         *
         * <p>Une correction porte des sacs et un poids négatifs, sans
         * prix ni montant : c'est de la matière qui sort au brassage.
         * L'écran la distingue, sans quoi un poids négatif au milieu des
         * entrées se lirait comme une erreur de saisie.</p>
         */
        String rowKind,
        /** Identifiant de la correction, quand la ligne en est une. */
        UUID correctionId
) {

    /** Une ligne de reçu, la forme historique. */
    public DayIntakeRowDto(UUID purchaseId, LocalDate date, String supplierName, String ref,
                           String deliveryRef, String supplierKind, Integer nbSacs,
                           BigDecimal weightKg, BigDecimal unitPrice, BigDecimal amount,
                           BigDecimal cumulativeQuantity, Integer cumulativeBags) {
        this(purchaseId, date, supplierName, ref, deliveryRef, supplierKind, nbSacs, weightKg,
                unitPrice, amount, cumulativeQuantity, cumulativeBags, "RECEIPT", null);
    }
}
