package com.ntech.cabosse.reception.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Une ligne brute du fichier d'import (déjà parsée côté client en CSV ou
 * XLSX). L'article n'apparaît pas ici — il est fourni une seule fois en
 * en-tête de la requête (cf. {@link DirectReceiptImportRequestDto}).
 *
 * <p>Tous les champs sont des chaînes pour préserver la valeur d'origine
 * du fichier et laisser le service appliquer le parsing FR-permissif
 * (espaces fines, virgules décimales, formats de date).</p>
 */
@Schema(description = "Ligne brute d'import de réception directe (1 producteur)")
public record DirectReceiptImportRowDto(
        int rowNumber,
        /** Date de réception au format libre (JJ/MM/AAAA ou ISO). Optionnel si defaultDate fournie. */
        String date,
        /** Code producteur — résolution exacte si renseigné. */
        String producerCode,
        /** Nom producteur — fallback de résolution, ou utilisé pour auto-création. */
        String producerName,
        /** Quantité (format FR ou US). */
        String quantity,
        /** Prix unitaire FCFA (entier ou décimal). */
        String unitPriceFcfa,
        /** N° bon de livraison de la ligne, optionnel. */
        String deliveryNoteRef,
        /** Notes ligne, optionnel. */
        String notes
) {}
