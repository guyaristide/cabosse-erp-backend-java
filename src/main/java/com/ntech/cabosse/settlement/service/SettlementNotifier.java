package com.ntech.cabosse.settlement.service;

import com.ntech.cabosse.notification.service.NotificationRouter;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.PermissionResolver;
import com.ntech.cabosse.settlement.entity.SettlementRequestEntity;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.entity.UserStatus;
import com.ntech.cabosse.user.repository.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

/**
 * Prévient qui doit trancher, puis qui doit payer, sur le circuit de
 * règlement.
 *
 * <p>Le circuit était livré sans alerte : la caissière déposait sa
 * demande et devait aller dire de vive voix au directeur qu'elle
 * l'attendait, puis surveiller elle-même la file pour savoir qu'il avait
 * tranché. Les avances aux délégués prévenaient depuis le 03/09/2026, le
 * règlement non (constaté le 14/09/2026).</p>
 *
 * <p>Deux moments, deux audiences. La demande va à qui approuve, et
 * l'échelon dépend du moyen et du montant : au second échelon, prévenir
 * la direction seule laisserait la demande dormir. L'accord va ensuite à
 * qui règle, demandeur compris, puisque c'est elle qui attend le feu
 * vert pour sortir l'argent.</p>
 *
 * <p>L'envoi ne fait jamais échouer le geste métier : une demande
 * enregistrée dont l'alerte n'est pas partie reste une demande
 * enregistrée.</p>
 */
@ApplicationScoped
public class SettlementNotifier {

    private static final Logger LOG = Logger.getLogger(SettlementNotifier.class);

    /** Au-delà, on cesse de parcourir : une structure n'a pas mille comptes. */
    private static final int MAX_USERS = 500;

    @Inject NotificationRouter router;
    @Inject UserRepository users;
    @Inject PermissionResolver permissions;
    @Inject TenantContext tenantContext;

    /** Une demande de règlement vient d'être déposée. */
    public void settlementAwaitsApproval(SettlementRequestEntity request) {
        try {
            UUID tenantId = tenantContext.tenantId();
            if (tenantId == null) return;
            // Au second échelon, seul le droit de gouvernance tranche :
            // alerter la direction lui annoncerait une décision qu'elle
            // ne peut pas prendre.
            Permission deciding = Boolean.TRUE.equals(request.governanceApprovalRequired)
                    ? Permission.COLLECTION_SETTLEMENT_APPROVE_GOVERNANCE
                    : Permission.COLLECTION_SETTLEMENT_APPROVE;
            String amount = request.requestedAmount == null
                    ? "0" : request.requestedAmount.toPlainString();
            router.route("settlement.pending-approval",
                    holdersOf(tenantId, deciding, null),
                    List.of(),
                    request.ref,
                    locale -> Messages.msg(locale, "m.ntf-settlement-pending-subject", request.ref),
                    locale -> Messages.msg(locale, "m.ntf-settlement-pending-body",
                            request.beneficiaryName == null ? "" : request.beneficiaryName,
                            amount, request.ref));
        } catch (RuntimeException e) {
            LOG.warnf(e, "Alerte d'approbation non enfilée pour le règlement %s", request.ref);
        }
    }

    /**
     * La demande a été tranchée : la caisse peut sortir l'argent.
     *
     * <p>Le message porte le montant <strong>accordé</strong> et le moyen
     * accordé : le règlement ne pourra employer ni un autre montant ni un
     * autre instrument, et l'apprendre au guichet ferait refaire le
     * geste.</p>
     */
    public void settlementApproved(SettlementRequestEntity request, UUID decidedBy) {
        try {
            UUID tenantId = tenantContext.tenantId();
            if (tenantId == null) return;
            String granted = request.approvedAmount == null
                    ? "0" : request.approvedAmount.toPlainString();
            String method = request.paymentMethod == null ? ""
                    : com.ntech.cabosse.shared.export.ExportEnumLabels.paymentMethod(
                            request.paymentMethod.name());
            router.route("settlement.approved",
                    holdersOf(tenantId, Permission.COLLECTION_PAYMENT_WRITE, decidedBy),
                    List.of(),
                    request.ref,
                    locale -> Messages.msg(locale, "m.ntf-settlement-approved-subject", request.ref),
                    locale -> Messages.msg(locale, "m.ntf-settlement-approved-body",
                            request.beneficiaryName == null ? "" : request.beneficiaryName,
                            granted, method, request.ref));
        } catch (RuntimeException e) {
            LOG.warnf(e, "Alerte de règlement non enfilée pour la demande %s", request.ref);
        }
    }

    /** Les comptes actifs qui portent ce droit, décideur exclu s'il y en a un. */
    private List<UserEntity> holdersOf(UUID tenantId, Permission right, UUID excluded) {
        return users.findByTenant(tenantId, 0, MAX_USERS).stream()
                .filter(u -> u.status == UserStatus.ACTIVE)
                .filter(u -> excluded == null || !u.id.equals(excluded))
                .filter(u -> permissions.of(u, tenantId).contains(right))
                .toList();
    }
}
