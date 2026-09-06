package com.ntech.cabosse.treasury.service;

import com.ntech.cabosse.accounting.entity.BankAccountEntity;
import com.ntech.cabosse.accounting.entity.BankAccountKind;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.PermissionResolver;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

/**
 * Qui voit le solde de quel compte de trésorerie (demande expert du
 * 05/09/2026).
 *
 * <p>Le solde de la banque est une information de gouvernance : seuls
 * ceux qui portent le droit de voir tous les soldes y accèdent. Une
 * caisse se lit aussi par la personne qui la gère, désignée sur le
 * compte : la caissière suit sa caisse sans voir la banque ni les caisses
 * des autres. Aucun rôle ni filière codé en dur : un droit, des
 * gestionnaires par compte, et l'administrateur assemble ses profils.</p>
 *
 * <p>Le compte lui-même reste visible partout (il faut pouvoir choisir où
 * un chèque se dépose) : seul son solde se masque.</p>
 */
@ApplicationScoped
public class BalanceVisibility {

    @Inject PermissionResolver permissions;
    @Inject TenantContext tenantContext;

    public boolean canSeeBalance(BankAccountEntity account) {
        java.util.Set<Permission> granted = permissions.current();
        if (granted.contains(Permission.TREASURY_BALANCE_ALL)) return true;
        if (account.kind == BankAccountKind.CAISSE
                && granted.contains(Permission.TREASURY_CASH_BALANCE)) {
            return true;
        }
        if (account.kind != BankAccountKind.CAISSE
                && granted.contains(Permission.TREASURY_BANK_BALANCE)) {
            return true;
        }
        // La désignation nominative vaut pour tout compte, banque
        // comprise : un comptable rattaché à un compte bancaire en lit le
        // solde sans voir les autres, comme la caissière avec sa caisse.
        UUID userId = tenantContext.userId();
        return userId != null
                && account.managerUserIds != null
                && account.managerUserIds.contains(userId);
    }
}
