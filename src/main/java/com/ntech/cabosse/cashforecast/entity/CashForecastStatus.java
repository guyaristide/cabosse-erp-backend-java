package com.ntech.cabosse.cashforecast.entity;

/**
 * Où en est un prévisionnel de décaissement.
 *
 * <p>Trois états et non deux : un prévisionnel déposé n'est pas encore
 * soumis. Le directeur le charge, le relit, le corrige, puis le soumet
 * ; le conseil ne doit pas se prononcer sur un brouillon.</p>
 */
public enum CashForecastStatus {
    /** Chargé, modifiable, invisible du conseil. */
    DRAFT,
    /** Soumis au conseil, en attente de sa décision. */
    SUBMITTED,
    APPROVED,
    REJECTED
}
