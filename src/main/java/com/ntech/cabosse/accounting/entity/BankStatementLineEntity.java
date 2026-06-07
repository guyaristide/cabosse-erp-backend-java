package com.ntech.cabosse.accounting.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Une ligne d'extrait bancaire — opération unitaire (encaissement, frais,
 * virement…). Tenant-scopée (collection {@code bank_statement_lines}).
 *
 * <p>Une ligne peut être rapprochée à une pièce comptable
 * ({@link #matchedPieceId}) lorsque les montants concordent. Le
 * {@link #sourceHash} est calculé à l'import pour bloquer les doublons
 * (réimport accidentel du même fichier).</p>
 */
public class BankStatementLineEntity {

    @BsonId
    public UUID id;

    public UUID statementId;
    public UUID bankAccountId;

    public LocalDate operationDate;

    /** Libellé brut tel qu'il apparaît sur l'extrait. */
    public String label;

    /** Montant positif. Le sens est porté par {@link #direction}. */
    public BigDecimal amountFcfa;

    /** {@code CREDIT} = entrée d'argent ; {@code DEBIT} = sortie. */
    public String direction;

    public BankStatementLineStatus status = BankStatementLineStatus.UNMATCHED;

    /** UUID de la {@code JournalPieceEntity} rapprochée, sinon null. */
    public UUID matchedPieceId;
    public Instant matchedAt;
    public String matchedByEmail;

    /**
     * Hash SHA-256 court (date + label normalisé + montant + direction)
     * — index unique par {@code bankAccountId} pour empêcher l'import
     * d'un même fichier deux fois.
     */
    public String sourceHash;

    public Instant createdAt;

    public BankStatementLineEntity() {}
}
