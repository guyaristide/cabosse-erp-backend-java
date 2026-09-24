package com.ntech.cabosse.tenant.dto;

import com.ntech.cabosse.user.entity.PermissionExceptionMode;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;

/**
 * Un droit accordé ou retiré à une personne seule (backlog ADM-03).
 *
 * <p>{@code grantedByEmail} et {@code grantedAt} sont posés par le
 * serveur : qui relit la fiche dans six mois doit savoir d'où vient ce
 * droit sans ouvrir le journal d'audit.</p>
 */
@Schema(description = "Exception de droit posée sur un utilisateur")
public record UserPermissionExceptionDto(

        @Schema(description = "Code de permission", required = true)
        String code,

        @Schema(description = "GRANT accorde, REVOKE retire", required = true)
        PermissionExceptionMode mode,

        @Schema(description = "Pourquoi cette personne, et pas son profil")
        String reason,

        @Schema(description = "Qui l'a posée, renseigné par le serveur", readOnly = true)
        String grantedByEmail,

        @Schema(description = "Quand elle a été posée, renseigné par le serveur", readOnly = true)
        Instant grantedAt
) {}
