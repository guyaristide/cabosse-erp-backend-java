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
 * <p>Trois niveaux, du plus précis au plus général : le taux fixé sur la
 * fiche du fournisseur, celui de sa catégorie, celui du tenant. Le premier
 * renseigné l'emporte, ce qui permet de traiter le cas particulier sans
 * défaire la règle générale.</p>
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

    public Margin resolve(TenantPreferences prefs, SupplierEntity supplier) {
        SupplierCategoryEntity category = supplier != null
                ? categories.findById(supplier.categoryId).orElse(null) : null;
        return resolve(prefs, supplier, category);
    }

    /** Variante sans lecture, quand la catégorie est déjà chargée. */
    public Margin resolve(TenantPreferences prefs, SupplierEntity supplier,
                          SupplierCategoryEntity category) {
        // Le mode suit le niveau qui a fixé le taux : appliquer un taux au
        // kilo dans un mode en pourcentage donnerait un montant absurde.
        String categoryMode = category != null ? category.marginMode : null;
        if (supplier != null && supplier.collectorMarginRate != null) {
            String mode = categoryMode != null ? categoryMode : prefs.delegateMarginMode();
            return new Margin(mode, supplier.collectorMarginRate, "supplier");
        }
        if (categoryMode != null) {
            return new Margin(categoryMode, category.marginRate, "category");
        }
        return new Margin(prefs.delegateMarginMode(), prefs.delegateMarginRate(), "tenant");
    }
}
