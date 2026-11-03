package com.ntech.cabosse.intake.dto;

import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * Demande de comptabilisation d'un bordereau : l'article et le site que
 * l'écran a fixés, et les lignes du fichier de traçabilité nationale.
 *
 * <p>L'article est facultatif depuis le 03/11/2026 : absent, c'est celui
 * du bordereau qui sert, et sans article nulle part la demande est
 * refusée.</p>
 */
@Schema(description = "Comptabilisation d'un bordereau de réception")
public record SntAccountingRequestDto(
        UUID articleId,
        UUID siteId,
        @NotNull List<SntLineDto> lines
) {}
