package com.ntech.cabosse.producerpurchase.dto;

import com.ntech.cabosse.accounting.entity.PostingSourceType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Une écriture née d'une réception, avec ce que le reçu porte.
 *
 * <p>Le comptable lit ici ce que les livraisons ont généré. Le montant
 * seul ne dit pas d'où il vient : une pièce de 240 000 F ne se vérifie
 * qu'en voyant le producteur, les sacs et le poids net qui la fondent.
 * Le reçu les porte, l'écriture les reprend (demandé le 06/10/2026).</p>
 *
 * <p>Une réception directe, qui n'a pas de reçu producteur, laisse ces
 * trois champs vides plutôt que d'y mettre zéro : zéro sac se lirait
 * comme une livraison vide.</p>
 */
public record CollectionEntryDto(
        UUID id,
        String ref,
        LocalDate date,
        PostingSourceType sourceType,
        UUID sourceId,
        String sourceRef,
        String libelle,
        BigDecimal amount,
        /** Le producteur nommé sur le reçu, celui dont la matière vient. */
        String producerName,
        /** Le délégué qui a livré pour lui, quand il y en a un. */
        String delegateName,
        Integer nbSacs,
        BigDecimal weightKg
) {}
