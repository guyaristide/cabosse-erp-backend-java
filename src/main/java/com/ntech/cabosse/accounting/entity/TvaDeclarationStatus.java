package com.ntech.cabosse.accounting.entity;

/**
 * États d'une déclaration TVA mensuelle.
 *
 * <ul>
 *   <li>{@link #A_PREPARER} — statut implicite : la déclaration n'est pas
 *       persistée tant que l'utilisateur n'a pas pris d'action.</li>
 *   <li>{@link #PRET_A_DEPOSER} — l'utilisateur a verrouillé les chiffres
 *       du mois. Les montants snapshot persistés correspondent aux
 *       agrégats {@code 4456}/{@code 4457} au moment du verrouillage.</li>
 *   <li>{@link #DEPOSE} — déclaration effectivement déposée auprès de la
 *       DGI ; n° de déclaration + date de dépôt enregistrés.</li>
 * </ul>
 */
public enum TvaDeclarationStatus {
    A_PREPARER,
    PRET_A_DEPOSER,
    DEPOSE
}
