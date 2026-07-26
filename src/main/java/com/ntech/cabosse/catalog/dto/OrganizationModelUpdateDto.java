package com.ntech.cabosse.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Payload d'écriture d'un modèle d'organisation. Le code est immuable
 * (aligné sur l'enum {@code TenantOrganizationModel}) donc absent du body :
 * il n'y a ni création ni suppression, uniquement une mise à jour.
 */
@Schema(description = "Payload de mise à jour d'un modèle d'organisation")
public record OrganizationModelUpdateDto(

        @NotBlank(message = "Libellé requis")
        @Size(min = 2, max = 80, message = "Libellé entre 2 et 80 caractères")
        String label,

        @Size(max = 300, message = "Description trop longue")
        String description,

        boolean isActive,

        @Schema(description = "Capacités fonctionnelles activées par ce modèle. Chaque entrée est le nom d'une valeur de TenantCapability (ex : HAS_MEMBERS, HAS_SUSTAINABILITY). Liste vide acceptée.",
                example = "[\"HAS_MEMBERS\", \"HAS_SUSTAINABILITY\"]")
        List<String> activates

) {}
