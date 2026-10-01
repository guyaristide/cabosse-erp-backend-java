package com.ntech.cabosse.tenant.dto;

import com.ntech.cabosse.tenant.service.TenantDataCategory;
import jakarta.validation.constraints.NotBlank;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.EnumSet;
import java.util.Set;

/**
 * Confirmation d'une remise à plat des données.
 *
 * <p>Le nom de la structure est recopié par l'appelant. C'est le seul
 * geste qui distingue une destruction voulue d'un clic malheureux, et il
 * n'existe aucune sauvegarde derrière.</p>
 *
 * <p>Les trois familles conservables sont absentes par défaut : une charge
 * utile ancienne efface tout, comme avant. Une option qui se serait
 * activée toute seule aurait fait le contraire de ce qu'on croyait
 * demander.</p>
 */
@Schema(description = "Confirmation d'une remise à plat des données")
public record TenantResetPayloadDto(

        @NotBlank(message = "{v.confirmation-requise}")
        @Schema(description = "Nom exact de la structure, recopié")
        String confirmation,

        @Schema(description = "Conserver le paramétrage : sites, exercices, "
                + "périodes comptables, campagnes, règles de notification, seuils de qualité")
        boolean keepSettings,

        @Schema(description = "Conserver les nomenclatures : plan comptable, articles, unités, "
                + "variétés, localités, types de dépense, centres de coût, programmes, recettes")
        boolean keepNomenclatures,

        @Schema(description = "Conserver le registre des tiers : producteurs et parcelles, "
                + "fournisseurs, clients")
        boolean keepParties

) {

    /** Les familles demandées, sous la forme que le service attend. */
    public Set<TenantDataCategory> keptCategories() {
        Set<TenantDataCategory> kept = EnumSet.noneOf(TenantDataCategory.class);
        if (keepSettings) {
            kept.add(TenantDataCategory.SETTINGS);
        }
        if (keepNomenclatures) {
            kept.add(TenantDataCategory.NOMENCLATURES);
        }
        if (keepParties) {
            kept.add(TenantDataCategory.PARTIES);
        }
        return kept;
    }
}
