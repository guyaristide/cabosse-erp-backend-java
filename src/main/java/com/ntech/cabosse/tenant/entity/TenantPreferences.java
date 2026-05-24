package com.ntech.cabosse.tenant.entity;

/**
 * Préférences opérationnelles du tenant. Sub-document de {@link TenantEntity}.
 *
 * <p>Ces valeurs sont chargées dans le JWT à la connexion pour éviter
 * une lecture du control plane à chaque requête côté frontend qui
 * formate dates / montants.</p>
 *
 * <p>Stockés en String (ISO codes) plutôt qu'en enums pour rester
 * extensibles sans migration de schéma.</p>
 */
public class TenantPreferences {

    /** ISO 4217 ({@code "XOF"}, {@code "GHS"}, {@code "EUR"}, …). */
    public String currency;

    /** ISO 639-1 ({@code "fr"}, {@code "en"}). */
    public String language;

    /** IANA Time Zone ({@code "Africa/Abidjan"}, etc.). */
    public String timezone;

    public TenantPreferences() {}
}
