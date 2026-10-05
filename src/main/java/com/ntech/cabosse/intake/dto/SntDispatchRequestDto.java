package com.ntech.cabosse.intake.dto;

import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * Comptabiliser plusieurs bordereaux d'un seul fichier de traçabilité.
 *
 * <p>Un mois de retard fait des dizaines de bordereaux, et le fichier
 * national qui les couvre est unique : le charger bordereau par
 * bordereau obligeait à le découper autant de fois (demandé le
 * 04/10/2026).</p>
 */
@Schema(description = "Comptabilisation de plusieurs bordereaux depuis un même extrait")
public record SntDispatchRequestDto(
        @NotNull UUID articleId,
        UUID siteId,
        /** Les bordereaux retenus, dans l'ordre que l'écran a montré. */
        @NotNull List<UUID> intakeIds,
        @NotNull List<SntLineDto> lines
) {}
