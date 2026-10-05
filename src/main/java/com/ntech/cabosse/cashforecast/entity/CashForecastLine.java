package com.ntech.cabosse.cashforecast.entity;

import java.math.BigDecimal;

/**
 * Une ligne du prévisionnel : un compte, un montant, une source de fonds.
 *
 * <p>Le libellé SYSCOHADA accompagne le numéro parce que le fichier du
 * conseil le porte, et qu'un état relu six mois plus tard doit se lire
 * sans le plan comptable à côté.</p>
 */
public class CashForecastLine {

    /** Numéro au plan comptable, tel que le fichier l'écrit. */
    public String account;

    /** Intitulé normalisé, repris du fichier. */
    public String accountLabel;

    /** Ce que la structure prévoit précisément, en clair. */
    public String detail;

    public BigDecimal amount;

    /** BANK ou CASH : d'où l'argent sortira. */
    public String source;

    public CashForecastLine() {}
}
