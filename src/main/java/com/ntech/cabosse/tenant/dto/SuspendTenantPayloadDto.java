package com.ntech.cabosse.tenant.dto;

import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Le motif d'une suspension.
 *
 * <p>Facultatif mais consigné : la structure demandera pourquoi, et une
 * coupure sans trace devient une coupure que personne n'assume.</p>
 */
@Schema(description = "Suspension de l'accès d'une structure")
public record SuspendTenantPayloadDto(
        @Size(max = 500, message = "{v.motif-trop-long}")
        String reason
) {}
