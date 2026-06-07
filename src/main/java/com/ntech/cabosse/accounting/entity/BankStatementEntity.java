package com.ntech.cabosse.accounting.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Extrait bancaire importé (1 fichier = 1 extrait = N lignes). Tenant-scopé
 * (collection {@code bank_statements}).
 *
 * <p>Sert d'enveloppe à un lot de {@link BankStatementLineEntity}. Le
 * {@link #matchedCount} est rafraîchi par le service de rapprochement à
 * chaque opération de match/unmatch — évite un count à chaque affichage.</p>
 */
public class BankStatementEntity {

    @BsonId
    public UUID id;

    /** FK vers {@link BankAccountEntity}. */
    public UUID bankAccountId;

    public String fileName;
    public Instant importedAt;
    public String importedByEmail;

    public LocalDate periodFrom;
    public LocalDate periodTo;

    public BigDecimal openingBalance;
    public BigDecimal closingBalance;

    public int lineCount;
    public int matchedCount;

    public BankStatementStatus status = BankStatementStatus.IMPORTED;

    public BankStatementEntity() {}
}
