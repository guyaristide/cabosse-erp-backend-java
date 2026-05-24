package com.ntech.cabosse.shared.storage;

/**
 * Scope d'un {@link CloudFileEntity} — détermine la base MongoDB où vit
 * la métadonnée et la convention de nom de chemin de stockage.
 *
 * <p>Règle (cf. {@code docs/file-storage.md} §2) :</p>
 * <ul>
 *   <li>{@link #PLATFORM} → {@code cabosse_control.cloud_files}. Fichiers
 *       possédés par la plateforme : logos tenants, icônes plans…</li>
 *   <li>{@link #TENANT} → {@code <tenant_db>.cloud_files}. Fichiers
 *       possédés par un tenant : images produits, attachements BC,
 *       exports… Le tenant est résolu via le {@code TenantContext}.</li>
 * </ul>
 *
 * <p>Le caller (service métier) choisit le scope explicitement à
 * l'upload — il n'y a pas de routage implicite par {@code type} pour
 * éviter toute confusion.</p>
 */
public enum CloudFileScope {

    /** Fichier de la plateforme. Vit dans {@code cabosse_control.cloud_files}. */
    PLATFORM,

    /** Fichier d'un tenant. Vit dans {@code <tenant_db>.cloud_files}. */
    TENANT
}
