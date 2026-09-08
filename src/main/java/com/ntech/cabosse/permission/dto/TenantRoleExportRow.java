package com.ntech.cabosse.permission.dto;

/**
 * Une ligne de l'export des profils : un droit d'un profil, à plat.
 *
 * <p>Demandé le 08/09/2026 pour le support : sur un environnement où
 * personne ne peut ouvrir la base, ce fichier dit exactement ce qu'un
 * profil permet, droit par droit, et signale ceux que les capacités du
 * tenant rendent inopérants.</p>
 */
public record TenantRoleExportRow(
        String profileName,
        String profileCode,
        String active,
        int userCount,
        String permissionCode,
        String permissionLabel,
        String inactive
) {}
