package com.ntech.cabosse.tenant.dto;

import com.ntech.cabosse.tenant.entity.TenantOrganizationModel;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Inputs d'une simulation de calcul de capacités tenant. Sert l'UI
 * backoffice (provisioning, édition) pour afficher en lecture seule les
 * capacités qui s'activeront avant validation du formulaire.
 *
 * <p>Aucun champ n'est strictement requis : un payload vide renvoie les
 * capacités d'un tenant {@code PRIVATE_COMPANY} sans activité ni
 * certification (typiquement vide).</p>
 */
@Schema(description = "Inputs pour simuler les capacités d'un tenant")
public record CapabilityPreviewPayloadDto(

        @Schema(description = "Structure organisationnelle. Défaut PRIVATE_COMPANY si null.",
                example = "COOPERATIVE")
        TenantOrganizationModel organizationModel,

        @Schema(description = "Codes des activités déclarées (référencent IndustryEntity.code).",
                example = "[\"cacao-production\", \"cafe\"]")
        List<String> industryCodes,

        @Schema(description = "Codes des certifications déclarées.",
                example = "[\"Fairtrade\", \"Bio\"]")
        List<String> certifications
) {}
