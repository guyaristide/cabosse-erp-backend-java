package com.ntech.cabosse.intake.dto;

import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * Demande de comptabilisation d'un bordereau : l'article et le site que
 * l'écran a fixés, et les lignes du fichier de traçabilité nationale.
 */
@Schema(description = "Comptabilisation d'un bordereau de réception")
public record SntAccountingRequestDto(
        @NotNull UUID articleId,
        UUID siteId,
        @NotNull List<SntLineDto> lines
) {}
