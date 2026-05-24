package com.ntech.cabosse.tenant.entity;

import java.util.UUID;

/**
 * Adresse du siège du tenant. Sub-document de {@link TenantEntity}.
 *
 * <p>{@code country} stocké en ISO 3166-1 alpha-2 (ex. {@code "CI"}, {@code "CM"},
 * {@code "FR"}). La validation contre la liste fermée est portée par les
 * DTOs de requête, pas par l'entité (qui doit pouvoir représenter
 * d'anciennes données potentiellement non normalisées).</p>
 *
 * <p>{@code regionCode} et {@code cityId} sont des références optionnelles
 * vers les catalogues du plan contrôle (collection {@code regions} et
 * {@code cities}). Restent nullables pour les pays/villes hors zone seedée
 * — dans ce cas seul {@code city} (texte libre) est rempli.</p>
 */
public class TenantAddress {

    public String street;
    /** Optionnel — pas tous les pays utilisent un code postal cohérent. */
    public String postalCode;
    public String city;
    /** ISO 3166-1 alpha-2, en majuscules. */
    public String country;
    /** FK optionnelle vers la collection {@code regions}. */
    public String regionCode;
    /** FK optionnelle vers la collection {@code cities}. */
    public UUID cityId;

    public TenantAddress() {}
}
