package com.ntech.cabosse.cashforecast.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ce que la structure prévoit de décaisser le mois prochain.
 *
 * <p>Le directeur charge ses prévisions avant le dernier jour ouvré du
 * mois en cours, et le conseil se prononce avant que l'argent ne parte
 * (demandé le 03/10/2026). Le conseil tenait ce tableau sur un
 * classeur : une ligne par compte, le montant, et d'où l'argent
 * sortira.</p>
 *
 * <p>Un prévisionnel par mois et par structure : deux pour le même mois
 * laisseraient le conseil approuver l'un et voir l'autre.</p>
 *
 * <p>Les totaux ne sont pas stockés. Ils se recalculent des lignes à
 * chaque lecture : figés, ils auraient fini par ne plus correspondre à
 * ce qu'on lit en dessous.</p>
 */
public class CashForecastEntity {

    @BsonId
    public UUID id;

    /** Le mois couvert, au format {@code 2026-11}. */
    public String month;

    public CashForecastStatus status = CashForecastStatus.DRAFT;

    public List<CashForecastLine> lines = new ArrayList<>();

    /** Ce que la caisse portait à l'ouverture du mois, tel que déclaré. */
    public BigDecimal openingCash;

    /** Et ce que la banque portait. */
    public BigDecimal openingBank;

    /** Ce que la structure compte encaisser dans le mois. */
    public BigDecimal expectedReceipts;

    public Instant createdAt;
    public String createdByEmail;
    public Instant submittedAt;
    public String submittedByEmail;
    public Instant decidedAt;
    public String decidedByEmail;
    public String rejectionReason;

    public long version = 0L;

    public CashForecastEntity() {}

    /** Le mois, quand il est lisible. */
    public YearMonth yearMonth() {
        try {
            return YearMonth.parse(month);
        } catch (Exception e) {
            return null;
        }
    }

    /** Somme des lignes : ce que le mois va coûter. */
    public BigDecimal totalOutflow() {
        return lines == null ? BigDecimal.ZERO : lines.stream()
                .map(l -> l.amount == null ? BigDecimal.ZERO : l.amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal openingTotal() {
        return nz(openingCash).add(nz(openingBank));
    }

    /**
     * Ce qui resterait à la fin du mois si tout se passait comme prévu.
     *
     * <p>C'est le chiffre que le conseil regarde : négatif, la structure
     * annonce qu'elle ne pourra pas tenir ses engagements.</p>
     */
    public BigDecimal projectedBalance() {
        return openingTotal().add(nz(expectedReceipts)).subtract(totalOutflow());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
