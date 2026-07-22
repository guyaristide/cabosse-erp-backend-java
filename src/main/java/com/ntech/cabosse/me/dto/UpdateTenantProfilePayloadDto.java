package com.ntech.cabosse.me.dto;

import com.ntech.cabosse.tenant.entity.LegalForm;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * Payload de mise à jour du profil coopérative (backlog COOP-03/04/05).
 * Remplacement complet des sections identité / adresse / contacts / produits.
 */
@Schema(description = "Mise à jour du profil de la coopérative")
public record UpdateTenantProfilePayloadDto(

        @NotBlank @Size(min = 2, max = 160) String name,

        // ─── Identité légale ───
        @Size(max = 200) String legalName,
        LegalForm legalForm,
        @Size(max = 40) String sigle,
        @Size(max = 60) String rccm,
        @Size(max = 60) String taxId,
        @Size(max = 60) String vatNumber,
        LocalDate constitutedAt,
        List<@Valid TenantAgrementDto> agrements,

        // ─── Adresse ───
        @Size(max = 200) String street,
        @Size(max = 20) String postalCode,
        @Size(max = 120) String city,
        @Size(max = 2) String country,
        @Size(max = 60) String regionCode,
        @Size(max = 60) String departmentCode,

        // ─── Contacts & produits ───
        List<@Valid TenantProfileContactDto> contacts,
        List<@Valid TenantProductDto> productsSold
) {}
