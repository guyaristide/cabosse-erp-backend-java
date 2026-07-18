package com.ntech.cabosse.accounting.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.UUID;

/**
 * Période comptable mensuelle verrouillée ({@code accounting_periods}).
 *
 * <p>Une période est <strong>ouverte par défaut</strong> : seul le
 * verrouillage crée un document. La réouverture ne supprime pas le
 * document (trace), elle passe {@code status} à {@code REOPENED} — la
 * période redevient modifiable et peut être verrouillée à nouveau.</p>
 *
 * <p>Le verrouillage bloque toute création de pièce dont la date tombe
 * dans la période (garde dans {@code AccountingService.postPiece}) : les
 * transitions métier qui génèrent une pièce (livraison, vente,
 * paiement, régularisation…) sont donc refusées sur période close.</p>
 */
public class AccountingPeriodEntity {

    public static final String STATUS_LOCKED = "LOCKED";
    public static final String STATUS_REOPENED = "REOPENED";

    @BsonId
    public UUID id;

    /** Mois comptable au format {@code YYYY-MM}. Unique. */
    public String period;

    /** {@link #STATUS_LOCKED} ou {@link #STATUS_REOPENED}. */
    public String status;

    public Instant lockedAt;
    public UUID lockedBy;
    public String lockedByEmail;

    public Instant reopenedAt;
    public UUID reopenedBy;
    public String reopenedByEmail;
    /** Motif de réouverture — obligatoire, tracé aussi dans l'audit. */
    public String reopenReason;
}
