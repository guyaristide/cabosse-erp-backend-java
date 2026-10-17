package com.ntech.cabosse.intake.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Ligne du fichier de détail de livraison par producteur, extrait du
 * système national de traçabilité (parsing client, épic CE-218). Champs
 * texte : le fichier du Conseil colle des espaces insécables dans les
 * nombres et les noms, la normalisation est faite côté service.
 */
@Schema(description = "Ligne d'un extrait de traçabilité nationale")
public record SntLineDto(
        int rowNumber,
        String reference,
        String date,
        /**
         * Sacs pesés sur la livraison.
         *
         * <p>Le magasinier compte les sacs sur son bordereau, le fichier
         * national ne les portait pas, et la comptabilisation créait des
         * reçus sans sacs : la fiche du jour additionnait des poids et
         * perdait les sacs (expert, 13/10/2026).</p>
         */
        String nbSacs,
        String weightKg,
        String amount,
        String amountCard,
        String amountCash,
        String producerName,
        String producerPhone,
        String delegateName,
        String delegatePhone,
        /**
         * Le statut de l'apporteur, tel que le terrain l'écrit :
         * planteur, fournisseur, délégué, ou ce que la coopérative
         * emploie.
         *
         * <p>Champ libre et facultatif. Le système sait déduire un
         * statut de la fiche de l'apporteur, mais il ne connaît que ses
         * deux catégories à lui ; le carnet, lui, en distingue d'autres
         * que personne n'a envie de faire entrer dans un référentiel
         * (expert, 17/10/2026). Renseigné, il l'emporte ; vide, le
         * statut déduit reste.</p>
         */
        String status
) {}
