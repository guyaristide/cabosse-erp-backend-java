package com.ntech.cabosse.crm.dto;

import com.ntech.cabosse.tenant.entity.TenantOrganizationModel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/** Ce qu'on sait d'un prospect, à sa création comme à sa reprise. */
@Schema(description = "Fiche d'un prospect")
public record ProspectUpsertDto(
        @NotBlank(message = "{v.nom-requis}") @Size(max = 160) String name,
        TenantOrganizationModel organizationModel,
        @Size(max = 80) String industry,
        @Size(max = 80) String country,
        @Size(max = 80) String city,
        @Size(max = 120) String source,
        List<@Valid ProspectContactDto> contacts,
        @Size(max = 160) String ownerEmail,
        LocalDate nextActionAt,
        @Size(max = 200) String nextActionLabel,
        @Size(max = 4000) String notes
) {}
