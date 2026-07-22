package com.ntech.cabosse.me.dto;

import com.ntech.cabosse.tenant.entity.LegalForm;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Profil coopérative du tenant courant (backlog COOP-03/04/05) : identité
 * légale, adresse, contacts et produits vendus. Réponse de lecture de
 * {@code /api/v1/me/tenant/profile}.
 */
@Schema(description = "Profil de la coopérative (identité, adresse, contacts, produits vendus)")
public record TenantProfileDto(
        UUID id,
        String name,

        // ─── Identité légale ───
        String legalName,
        LegalForm legalForm,
        String sigle,
        String rccm,
        String taxId,
        String vatNumber,
        LocalDate constitutedAt,
        List<TenantAgrementDto> agrements,

        // ─── Adresse ───
        String street,
        String postalCode,
        String city,
        String country,
        String regionCode,
        String departmentCode,

        // ─── Contacts & produits ───
        List<TenantProfileContactDto> contacts,
        List<TenantProductDto> productsSold
) {}
