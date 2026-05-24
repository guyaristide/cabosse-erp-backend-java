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

        @NotEmpty(message = "Au moins une activité requise")
        @Size(max = 12, message = "Au plus 12 activités par tenant")
        List<@Valid Line> activities

) {

    @Schema(description = "Une activité")
    public record Line(
            @NotBlank(message = "Code activité requis")
            @Size(max = 60)
            String code,

            @NotBlank(message = "Libellé requis")
            @Size(max = 120)
            String label,

            @NotNull(message = "isPrimary requis (true | false)")
            Boolean isPrimary
    ) {}
}
