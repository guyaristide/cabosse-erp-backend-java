package com.ntech.cabosse.catalog.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Variante admin du DTO pays : ajoute {@code isActive} (filtré dans la
 * vue publique). Réservé aux endpoints {@code /admin/catalog/*}.
 */
@Schema(description = "Pays : vue admin (avec activation)")
public record CountryAdminDto(
        String code,
        String nameFr,
        String nameEn,
        String dialCode,
        boolean isActive
) {}
