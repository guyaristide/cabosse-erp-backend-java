package com.ntech.cabosse.tracabilite.dto;

import java.util.List;

/**
 * Une étape de la chaîne de transformation d'un lot.
 *
 * @param kind     {@code origine|achat|production|lots-filles|livraison}
 * @param position label court ("01", "02"…) pour l'affichage
 * @param title    libellé fonctionnel ("Réception BC-…")
 * @param date     date ou plage de dates ISO ou texte
 * @param pieceRef référence d'une pièce métier si l'étape pointe une route
 * @param pieceHref lien interne front si applicable
 * @param details  description multi-ligne lisible par un auditeur
 * @param actor    acteur principal (fournisseur, opérateur, site)
 * @param status   {@code validated|in-progress|pending}
 */
public record StageDto(
        String id,
        String kind,
        String position,
        String title,
        String date,
        String pieceRef,
        String pieceHref,
        List<String> details,
        String actor,
        String status
) {}
