package com.ntech.cabosse.accounting.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Brouillon d'opération diverse ({@code od_drafts}) — backlog CPT-06/07.
 *
 * <p>Seules les OD saisies à la main passent par un cycle brouillon puis
 * validée (décision du 18/07/2026, option B) : les pièces automatiques
 * naissent validées, le journal {@code journal_pieces} ne contient jamais
 * de brouillon. À la validation, une pièce immuable est créée via
 * {@code AccountingService.postPiece} (source {@code MANUAL_ENTRY},
 * idempotence sur l'id du brouillon) et ce document passe
 * {@code VALIDATED} avec la référence de la pièce.</p>
 *
 * <p>Un brouillon peut être déséquilibré ou incomplet pendant la saisie ;
 * l'équilibre débit/crédit et l'existence des comptes au plan ne sont
 * exigés qu'à la validation. La clôture d'une période refuse s'il reste
 * des brouillons datés dans le mois.</p>
 */
public class OdDraftEntity {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_VALIDATED = "VALIDATED";

    @BsonId
    public UUID id;

    /** Date comptable visée par l'OD. */
    public LocalDate date;

    /** Libellé de la future pièce (obligatoire dès la saisie). */
    public String libelle;

    /** Lignes débit/crédit — même forme que les pièces du journal. */
    public List<JournalEntry> entries;

    /** {@link #STATUS_DRAFT} ou {@link #STATUS_VALIDATED}. */
    public String status;

    /** Référence de la pièce générée à la validation. */
    public String pieceRef;

    public Instant createdAt;
    public UUID createdBy;
    public String createdByEmail;
    public Instant updatedAt;
    public Instant validatedAt;
    public UUID validatedBy;
}
