package com.ntech.cabosse.cashsupply.service;

import com.ntech.cabosse.cashsupply.entity.CashSupplyRequestEntity;
import com.ntech.cabosse.notification.service.NotificationRouter;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.PermissionResolver;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.entity.UserStatus;
import com.ntech.cabosse.user.repository.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Prévient la main suivante qu'un approvisionnement l'attend.
 *
 * <p>Le circuit n'a de sens que si chacun apprend son tour sans aller le
 * guetter : la caisse se vide un jour donné, et une demande qui dort
 * trois jours dans une file que personne n'ouvre arrête les achats.</p>
 *
 * <p>L'envoi ne fait jamais échouer la demande. Un approvisionnement
 * enregistré dont l'alerte n'est pas partie reste enregistré ; l'inverse
 * ferait perdre une saisie pour une raison qui ne regarde pas celui qui
 * l'a faite.</p>
 */
@ApplicationScoped
public class CashSupplyNotifier {

    private static final Logger LOG = Logger.getLogger(CashSupplyNotifier.class);

    /** Au-delà, on cesse de parcourir : une structure n'a pas mille comptes. */
    private static final int MAX_USERS = 500;

    @Inject NotificationRouter router;
    @Inject UserRepository users;
    @Inject PermissionResolver permissions;
    @Inject TenantContext tenantContext;

    /** Une demande vient d'être déposée et attend sa décision. */
    public void supplyAwaitsApproval(CashSupplyRequestEntity e) {
        try {
            UUID tenantId = tenantContext.tenantId();
            if (tenantId == null) return;
            router.route("cash-supply.pending-approval",
                    holders(tenantId, Permission.CASH_SUPPLY_APPROVE, e.createdBy),
                    e.createdBy != null ? List.of(e.createdBy) : List.of(),
                    e.ref,
                    locale -> Messages.msg(locale, "m.ntf-cash-supply-pending-subject", e.ref),
                    locale -> Messages.msg(locale, "m.ntf-cash-supply-pending-body",
                            e.cashAccountLabel == null ? "" : e.cashAccountLabel,
                            amount(e.requestedAmount), e.ref));
        } catch (RuntimeException ex) {
            LOG.warnf(ex, "Alerte d'approbation non enfilée pour l'approvisionnement %s", e.ref);
        }
    }

    /**
     * L'approvisionnement est accordé : la caisse peut préparer le chèque.
     *
     * <p>Le message porte le montant <strong>accordé</strong> : c'est
     * celui du chèque, et annoncer le montant sollicité ferait préparer
     * un chèque de trop.</p>
     */
    public void supplyAwaitsFulfilment(CashSupplyRequestEntity e) {
        try {
            UUID tenantId = tenantContext.tenantId();
            if (tenantId == null) return;
            router.route("cash-supply.approved",
                    holders(tenantId, Permission.TREASURY_WRITE, e.approvedBy),
                    e.approvedBy != null ? List.of(e.approvedBy) : List.of(),
                    e.ref,
                    locale -> Messages.msg(locale, "m.ntf-cash-supply-approved-subject", e.ref),
                    locale -> Messages.msg(locale, "m.ntf-cash-supply-approved-body",
                            e.cashAccountLabel == null ? "" : e.cashAccountLabel,
                            amount(e.effectiveAmount()), e.ref));
        } catch (RuntimeException ex) {
            LOG.warnf(ex, "Alerte d'exécution non enfilée pour l'approvisionnement %s", e.ref);
        }
    }

    /**
     * Les comptes actifs qui portent ce droit, sauf celui qui vient
     * d'agir : l'inviter à décider de son propre geste l'enverrait
     * au-devant d'un refus.
     */
    private List<UserEntity> holders(UUID tenantId, Permission right, UUID exclude) {
        return users.findByTenant(tenantId, 0, MAX_USERS).stream()
                .filter(u -> u.status == UserStatus.ACTIVE)
                .filter(u -> u.email != null && !u.email.isBlank())
                .filter(u -> exclude == null || !u.id.equals(exclude))
                .filter(u -> permissions.of(u, tenantId).contains(right))
                .toList();
    }

    private static String amount(BigDecimal value) {
        return value == null ? "0" : value.toPlainString();
    }
}
