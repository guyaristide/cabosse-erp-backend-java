package com.ntech.cabosse.me.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Payload de remplacement complet de la liste des activités du tenant
 * courant. Sémantique PUT : l'ancienne liste est entièrement remplacée
 * par la nouvelle.
 *
 * <p>Contraintes vérifiées côté service :</p>
 * <ul>
 *   <li>1..N activités,</li>
 *   <li>exactement une activité avec {@code isPrimary=true},</li>
 *   <li>chaque {@code code} doit exister dans la collection système
 *       {@code industries} (sinon BusinessException).</li>
 * </ul>
 */
@Schema(description = "Remplace la liste des activités du tenant courant")
public record UpdateTenantActivitiesDto(

        @NotEmpty(message = "{v.au-moins-une-activite-requise}")
        @Size(max = 12, message = "{v.au-plus-12-activites-par-tenant}")
        List<@Valid Line> activities

) {

    @Schema(description = "Une activité")
    public record Line(
            @NotBlank(message = "{v.code-activite-requis}")
            @Size(max = 60)
            String code,

            @NotBlank(message = "{v.libelle-requis}")
            @Size(max = 120)
            String label,

            @NotNull(message = "{v.isprimary-requis-true-false}")
            Boolean isPrimary
    ) {}
}
