package com.ntech.cabosse.suppliercategory.service;

import com.ntech.cabosse.supplier.entity.SupplierEntity;
import com.ntech.cabosse.suppliercategory.entity.SupplierCategoryEntity;
import com.ntech.cabosse.suppliercategory.repository.SupplierCategoryRepository;
import com.ntech.cabosse.tenant.entity.TenantPreferences;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Rémunération appliquée à un fournisseur qui apporte de la matière
 * (backlog ACH-07).
 *
 * <p>Quatre niveaux, du plus précis au plus général : le taux convenu
 * avec ce délégué <strong>pour cette campagne</strong>, son taux commun,
 * celui de sa catégorie, celui du tenant. Le premier renseigné l'emporte,
 * ce qui permet de traiter le cas particulier sans défaire la règle
 * générale.</p>
 *
 * <p>La campagne vient en tête parce qu'elle se négocie : la marge d'une
 * saison n'engage pas la suivante, et un délégué qui obtient mieux cette
 * année ne doit pas voir son historique réécrit.</p>
 *
 * <p>Une marge de campagne s'exprime <strong>toujours en FCFA par
 * kilo</strong>. C'est ce qui s'ajoute au prix bord champ pour former le
 * prix barème, et le mode retenu ailleurs par la structure ne s'y applique
 * pas : un pourcentage ne s'ajoute pas à un prix unitaire.</p>
 */
@ApplicationScoped
public class SupplierMarginResolver {

    @Inject SupplierCategoryRepository categories;

    /** Mode et taux effectivement applicables, et d'où ils viennent. */
    public record Margin(String mode, BigDecimal rate, String source) {

        public boolean none() {
            return mode == null || TenantPreferences.DELEGATE_MARGIN_NONE.equals(mode)
                    || rate == null || rate.signum() <= 0;
        }

        /**
         * La marge s'exprime-t-elle en FCFA par kilo ?
         *
         * <p>Seul ce mode permet de composer un prix barème avec le prix
         * bord champ : un pourcentage ne s'ajoute pas à un prix unitaire.</p>
         */
        public boolean isPerKg() {
            return !none() && TenantPreferences.DELEGATE_MARGIN_PER_KG.equals(mode);
        }

        /** Rémunération due sur un apport, dans le mode retenu. */
        public BigDecimal on(BigDecimal weightKg, BigDecimal amountFcfa) {
            if (none()) return BigDecimal.ZERO;
            if (TenantPreferences.DELEGATE_MARGIN_PER_KG.equals(mode)) {
                return weightKg.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            }
            return amountFcfa.multiply(rate)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }
    }

    public Margin resolve(TenantPreferences prefs, SupplierEntity supplier,
                          java.util.UUID campaignId) {
        SupplierCategoryEntity category = supplier != null
                ? categories.findById(supplier.categoryId).orElse(null) : null;
        return resolve(prefs, supplier, category, campaignId);
    }

    /** Variante sans lecture, quand la catégorie est déjà chargée. */
    public Margin resolve(TenantPreferences prefs, SupplierEntity supplier,
                          SupplierCategoryEntity category, java.util.UUID campaignId) {
        // Le mode suit le niveau qui a fixé le taux : appliquer un taux au
        // kilo dans un mode en pourcentage donnerait un montant absurde.
        String categoryMode = category != null ? category.marginMode : null;
        BigDecimal forCampaign = campaignRate(supplier, campaignId);
        if (forCampaign != null) {
            // Une marge de campagne est toujours un montant par kilo, quel
            // que soit le mode retenu ailleurs. C'est ce qui s'ajoute au
            // prix bord champ pour former le prix barème du délégué, et un
            // pourcentage ne s'ajoute pas à un prix unitaire : lu dans le
            // mode du tenant, un montant en FCFA deviendrait un taux et le
            // barème cesserait de se calculer.
            return new Margin(TenantPreferences.DELEGATE_MARGIN_PER_KG, forCampaign, "campaign");
        }
        if (supplier != null && supplier.collectorMarginRate != null) {
            String mode = categoryMode != null ? categoryMode : prefs.delegateMarginMode();
            return new Margin(mode, supplier.collectorMarginRate, "supplier");
        }
        if (categoryMode != null) {
            return new Margin(categoryMode, category.marginRate, "category");
        }
        return new Margin(prefs.delegateMarginMode(), prefs.delegateMarginRate(), "tenant");
    }

    /**
     * Le taux convenu avec ce délégué pour cette campagne, s'il existe.
     *
     * <p>Sans campagne connue, on ne devine pas : une opération hors
     * campagne prend le taux commun plutôt que celui d'une saison prise au
     * hasard.</p>
     */
    private static BigDecimal campaignRate(SupplierEntity supplier, java.util.UUID campaignId) {
        if (supplier == null || campaignId == null || supplier.collectorMarginByCampaign == null) {
            return null;
        }
        return supplier.collectorMarginByCampaign.stream()
                .filter(m -> campaignId.equals(m.campaignId) && m.rate != null)
                .map(m -> m.rate)
                .findFirst().orElse(null);
    }
}
