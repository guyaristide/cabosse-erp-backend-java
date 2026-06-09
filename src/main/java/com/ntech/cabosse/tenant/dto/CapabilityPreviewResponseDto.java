package com.ntech.cabosse.tenant.dto;

import com.ntech.cabosse.tenant.capability.TenantCapability;
import com.ntech.cabosse.tenant.capability.TenantCapabilityService.CapabilitySource;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Réponse de simulation de capacités. Chaque entrée correspond à une
 * capacité activée par la combinaison (organizationModel + industries +
 * certifications). Une capacité non activée n'apparaît pas dans la liste.
 *
 * <p>Les {@code sources} expliquent pourquoi chaque capacité est activée.
 * Une capacité peut avoir plusieurs sources (ex : HAS_SUSTAINABILITY
 * activée à la fois par organisation COOPERATIVE et par certification
 * Fairtrade).</p>
 */
@Schema(description = "Capacités effectives calculées + sources d'activation")
public record CapabilityPreviewResponseDto(
        List<CapabilityEntry> capabilities
) {

    @Schema(description = "Une capacité avec ses sources d'activation")
    public record CapabilityEntry(
            TenantCapability name,
            List<CapabilitySource> sources
    ) {}
}
