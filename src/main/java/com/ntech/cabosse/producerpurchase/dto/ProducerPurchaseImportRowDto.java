package com.ntech.cabosse.producerpurchase.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Ligne d'import d'un reçu d'achat producteur (parsing client), backlog NEG-01.
 *
 * <p>Champs texte, validés et normalisés côté service. Le fichier de terrain
 * mélange trois natures de colonnes : les constantes de la coopérative, qui
 * ne sont pas relues ; les rappels de la fiche producteur, qui servent de
 * contrôle ; et les données de la transaction, seules à être enregistrées.
 * Seules les dernières figurent ici.</p>
 *
 * <p>{@code producerRef} porte le numéro interne et {@code producerCardRef}
 * le numéro de carte : deux espaces distincts, pour qu'un code interne ne
 * puisse pas l'emporter sur la carte d'un autre producteur. Une seule
 * colonne renseignée est cherchée dans les deux. {@code campaignLabel} vient du fichier et
 * prime sur {@code campaignId} choisi à l'écran, ce qui permet un fichier
 * couvrant deux campagnes. {@code siteId} est fixé à l'import.</p>
 */
@Schema(description = "Ligne d'import reçu d'achat producteur")
public record ProducerPurchaseImportRowDto(
        int rowNumber,
        String officialReceiptRef,
        String producerRef,
        /** N° de carte du producteur, cherché parmi les pièces identifiantes. */
        String producerCardRef,
        String producerName,
        String productCode,
        String date,
        String siteId,
        String campaignId,
        String campaignLabel,
        String nbSacs,
        String weightKg,
        String price,
        String amount,
        String amountPaid,
        String paymentMethod,
        String paymentRef,
        String delegateCode,
        String delegateName
) {}
