package com.ntech.cabosse.expense.service;

import com.ntech.cabosse.expense.entity.DirectExpenseKind;
import com.ntech.cabosse.tenant.entity.TenantPreferences;
import com.ntech.cabosse.tenant.service.TenantPreferencesLookup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;

/**
 * Qui doit se prononcer avant qu'une dépense ne soit payée.
 *
 * <p>Son propre circuit, distinct de celui des règlements aux
 * producteurs et délégués (tranché le 03/10/2026). Une facture
 * d'électricité et un solde de campagne ne se décident ni par les mêmes
 * personnes ni sur les mêmes montants : un seul réglage pour les deux
 * aurait obligé la structure à choisir le plus contraignant des deux.</p>
 *
 * <p>Le motif suit celui des règlements, pour que les deux écrans se
 * lisent de la même façon : un périmètre, un seuil d'approbation, un
 * seuil de gouvernance au-delà duquel un second échelon se prononce.</p>
 */
@ApplicationScoped
public class ExpenseApprovalRule {

    @Inject TenantPreferencesLookup preferences;

    /** La dépense doit-elle être approuvée avant d'être réglée ? */
    public boolean required(DirectExpenseKind kind, BigDecimal amount) {
        TenantPreferences prefs = preferences.current();
        if (prefs == null) return false;
        boolean inScope = switch (prefs.expenseApprovalScope()) {
            case TenantPreferences.EXPENSE_APPROVAL_ALL -> true;
            case TenantPreferences.EXPENSE_APPROVAL_SUBSCRIPTIONS ->
                    kind == DirectExpenseKind.CONTRACT;
            case TenantPreferences.EXPENSE_APPROVAL_PETTY_CASH ->
                    kind == DirectExpenseKind.PETTY_CASH;
            default -> false;
        };
        if (!inScope) return false;
        return nz(amount).compareTo(prefs.expenseApprovalThreshold()) >= 0;
    }

    /**
     * Le second échelon se prononce-t-il aussi ?
     *
     * <p>Sans seuil de gouvernance, il n'y a qu'un échelon : le
     * directeur tranche seul. Avec, le conseil se prononce au-delà.</p>
     */
    public boolean governanceRequired(BigDecimal amount) {
        TenantPreferences prefs = preferences.current();
        if (prefs == null || prefs.expenseGovernanceThreshold == null) return false;
        return nz(amount).compareTo(prefs.expenseGovernanceThreshold) >= 0;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
