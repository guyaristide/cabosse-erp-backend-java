package com.ntech.cabosse.accounting.service;

import com.ntech.cabosse.accounting.entity.JournalEntry;
import com.ntech.cabosse.accounting.entity.PostingSourceType;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Demande de comptabilisation faite à {@link AccountingService#postPiece}.
 * Construite par les helpers {@code postFromXxx(...)} à partir des entités
 * métier (BC, RD, Vente, paiement).
 *
 * @param date       date de comptabilisation (= date métier de l'événement source)
 * @param sourceType origine fonctionnelle, sert l'idempotence
 * @param sourceId   UUID de l'agrégat source
 * @param sourceRef  référence affichable de la source (ex. "BC-2026-0001")
 * @param libelle    libellé de la pièce
 * @param entries    lignes débit/crédit ; somme débits = somme crédits sinon refus
 */
public record PostingRequest(
        LocalDate date,
        PostingSourceType sourceType,
        UUID sourceId,
        String sourceRef,
        String libelle,
        List<JournalEntry> entries
) {}
