package com.ntech.cabosse.treasury.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Comptage physique d'une caisse à une date, confronté au solde que la
 * comptabilité attend. Tenant-scopé ({@code cash_counts}).
 *
 * <p>C'est le geste que la caissière fait déjà sur papier en fin de
 * semaine. Le conserver dans le système lui donne deux choses qu'elle
 * n'avait pas : le solde théorique calculé sans effort, et l'historique
 * des écarts, qui dit si un décalage est un accident ou une habitude.</p>
 */
public class CashCountEntity {

    @BsonId
    public UUID id;

    /** Référence affichable {@code PDC-YYYY-NNNN} (point de caisse). */
    public String ref;

    public UUID accountId;
    public String accountLabel;
    public String syscohadaAccount;

    public LocalDate countedAt;

    /** Solde attendu d'après les écritures, à la date du comptage. */
    public BigDecimal theoreticalFcfa;

    /** Somme réellement comptée en caisse. */
    public BigDecimal countedFcfa;

    /** Compté moins théorique. Négatif quand il manque de l'argent. */
    public BigDecimal discrepancyFcfa;

    /**
     * Écriture de régularisation, lorsque l'écart a été constaté
     * comptablement. Vide tant qu'il reste à expliquer.
     */
    public String pieceRef;

    public String notes;
    public String countedByEmail;
    public Instant createdAt;
    public UUID createdBy;

    public CashCountEntity() {}
}
