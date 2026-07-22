package com.ntech.cabosse.producerpurchase.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Ligne d'import d'un reçu d'achat producteur (parsing client), backlog NEG-01.
 * Champs texte : validés / normalisés côté service. {@code producerRef}
 * rapproche le producteur par N° carte CCC ou N° interne. {@code siteId} et
 * {@code campaignId} sont fixés à l'import (mêmes pour toutes les lignes).
 */
@Schema(description = "Ligne d'import reçu d'achat producteur")
public record ProducerPurchaseImportRowDto(
        int rowNumber,
        String producerRef,
        String productCode,
        String date,
        String siteId,
        String campaignId,
        String nbSacs,
        String weightKg,
        String price,
        String amount,
        String paymentMethod,
        String paymentRef,
        String payerName
) {}
