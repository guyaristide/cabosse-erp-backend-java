package com.ntech.cabosse.accounting.entity;

import com.ntech.cabosse.accounting.entity.JournalEntry;
import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Écriture retenue parce que sa période comptable était close quand elle
 * est arrivée.
 *
 * <p>Le cas vient du terrain : un achat du 30 septembre saisi hors ligne,
 * synchronisé le 5 octobre alors que septembre est clôturé. Auparavant
 * l'écriture était refusée et la saisie perdue. Elle attend désormais ici
 * que le comptable tranche, parce que déplacer une écriture d'une période
 * à l'autre change le résultat de deux exercices : c'est une décision
 * comptable, pas un automatisme du logiciel.</p>
 *
 * <p>Volontairement <strong>hors du journal</strong> : tant qu'elle n'est
 * pas régularisée, elle ne doit peser sur aucun état, ni balance, ni
 * compte de résultat. Une pièce marquée dans le journal finirait par être
 * comptée par un export qui aurait oublié de la filtrer.</p>
 */
public class QuarantinedPostingEntity {

    @BsonId
    public UUID id;

    /** Origine métier, reprise telle quelle de la demande d'écriture. */
    public PostingSourceType sourceType;
    public UUID sourceId;
    public String sourceRef;

    /** Date d'effet d'origine, celle qui tombe dans la période close. */
    public LocalDate date;
    public String libelle;

    /** Lignes de l'écriture, conservées pour être passées telles quelles. */
    public List<JournalEntry> entries = new ArrayList<>();

    public BigDecimal totalDebitFcfa;
    public BigDecimal totalCreditFcfa;

    /** Période verrouillée qui a provoqué la mise en attente. */
    public String lockedPeriod;

    public QuarantineStatus status;

    /** Date de traitement par le comptable, et ce qu'il en a fait. */
    public Instant resolvedAt;
    public String resolvedByEmail;
    /** Référence de la pièce produite si l'écriture a finalement été passée. */
    public String resultingPieceRef;
    /** Motif saisi en cas d'abandon. */
    public String discardReason;

    public Instant createdAt;

    public QuarantinedPostingEntity() {}
}
