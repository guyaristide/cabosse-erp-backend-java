package com.ntech.cabosse.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Confirmation d'une remise à plat des données.
 *
 * <p>Le nom de la structure est recopié par l'appelant. C'est le seul
 * geste qui distingue une destruction voulue d'un clic malheureux, et il
 * n'existe aucune sauvegarde derrière.</p>
 */
@Schema(description = "Confirmation d'une remise à plat des données")
public record TenantResetPayloadDto(

        @NotBlank(message = "{v.confirmation-requise}")
        @Schema(description = "Nom exact de la structure, recopié")
        String confirmation

) {}
