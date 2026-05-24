package com.ntech.cabosse.tenant.entity;

import java.util.UUID;

/**
 * Branding visuel d'un tenant. Sub-document de {@link TenantEntity}.
 *
 * <p>Le binaire du logo n'est <strong>pas</strong> stocké ici, ni dans
 * aucune autre entité métier (cf. règle CLAUDE.md §6.4). Il vit dans un
 * {@code CloudFileEntity} accédé via {@code FileUploadService}.</p>
 *
 * <p>Seul l'{@link #logoFileId} est la référence canonique. Les deux
 * autres champs ({@link #mimeType} et {@link #sizeBytes}) sont
 * <strong>dupliqués</strong> pour permettre l'affichage rapide "logo
 * personnalisé · 124 ko" dans les listes paginées sans tirer le
 * {@code CloudFileEntity}. Ces deux champs sont réécrits par
 * {@code TenantLogoService} à chaque upload ; toute autre source de
 * vérité (chemin, nom original, dates) reste dans
 * {@code CloudFileEntity}.</p>
 */
public class TenantBranding {

    /** Couleur de marque en hexa ({@code #1A1A1A} par défaut). */
    public String brandColor;

    /** UUID du {@code CloudFileEntity} qui porte le logo. Null si pas de logo. */
    public UUID logoFileId;

    /** Cache du mime type pour l'affichage rapide ({@code image/png}, {@code image/svg+xml}, …). */
    public String mimeType;

    /** Cache de la taille en octets pour l'affichage rapide. Null si pas de logo. */
    public Long sizeBytes;

    public TenantBranding() {}

    public TenantBranding(String brandColor) {
        this.brandColor = brandColor;
    }
}
