package com.ntech.cabosse.settings.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * La liste complète des canaux, telle que l'écran la pose. Un canal absent
 * est retiré. Pour un webhook, une cible vide conserve l'adresse déjà
 * enregistrée : le secret ne repasse jamais par le navigateur.
 */
@Schema(description = "Écriture des canaux d'alerte de l'éditeur")
public record EditorAlertsUpsertDto(
        @NotNull @Valid @Size(max = 20) List<Channel> channels
) {
    public record Channel(
            @Size(max = 40) String id,
            @NotBlank @Size(max = 20) String type,
            @Size(max = 80) String label,
            @Size(max = 500) String target,
            boolean enabled,
            @Size(max = 10) List<@Size(max = 40) String> events
    ) {}
}
