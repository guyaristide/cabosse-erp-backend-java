package com.ntech.cabosse.collector.service;

import com.ntech.cabosse.collector.entity.AdvanceRefundEntity;
import com.ntech.cabosse.notification.entity.NotificationChannel;
import com.ntech.cabosse.notification.entity.NotificationUsage;
import com.ntech.cabosse.notification.service.NotificationQueue;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.PermissionResolver;
import com.ntech.cabosse.shared.i18n.Locales;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.service.TenantPreferencesLookup;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.entity.UserStatus;
import com.ntech.cabosse.user.repository.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Alertes du circuit de reliquat d'avance (CE-187), sur le patron des
 * alertes d'avances : destinataires désignés par leur droit, auteur du
 * geste précédent exclu (deux paires d'yeux), corps rendu dans la langue
 * du destinataire avant la mise en file, et l'envoi ne fait jamais
 * échouer l'opération métier.
 */
@ApplicationScoped
public class AdvanceRefundNotifier {

    private static final Logger LOG = Logger.getLogger(AdvanceRefundNotifier.class);

    /** Au-delà, on cesse de parcourir : une structure n'a pas mille comptes. */
    private static final int MAX_USERS = 500;

    @Inject NotificationQueue queue;
    @Inject UserRepository users;
    @Inject PermissionResolver permissions;
    @Inject TenantPreferencesLookup preferences;
    @Inject TenantContext tenantContext;

    /** Une demande vient d'être déposée : ceux qui approuvent sont prévenus. */
    public void refundAwaitsApproval(AdvanceRefundEntity refund) {
        enqueueSafely(refund, Permission.COLLECTION_ADVANCE_APPROVE, refund.requestedByEmail,
                "m.ntf-refund-pending-subject", "m.ntf-refund-pending-body",
                "advance-refund.pending-approval");
    }

    /** Approuvé : ceux qui décaissent sont prévenus, l'approbateur exclu. */
    public void refundAwaitsPayment(AdvanceRefundEntity refund) {
        enqueueSafely(refund, Permission.COLLECTION_ADVANCE_DISBURSE, refund.decidedByEmail,
                "m.ntf-refund-approved-subject", "m.ntf-refund-approved-body",
                "advance-refund.awaiting-payment");
    }

    /** Reporté : la caissière qui a demandé sait que le crédit reste au compte. */
    public void refundReported(AdvanceRefundEntity refund) {
        try {
            if (refund.requestedByEmail == null) return;
            UUID tenantId = tenantContext.tenantId();
            UserEntity requester = users.findByTenant(tenantId, 0, MAX_USERS).stream()
                    .filter(u -> u.status == UserStatus.ACTIVE && u.email != null
                            && u.email.equalsIgnoreCase(refund.requestedByEmail))
                    .findFirst().orElse(null);
            if (requester == null) return;
            Locale locale = Locales.firstOf(requester.locale, preferences.current().language);
            queue.enqueue(new NotificationQueue.Request(
                    NotificationChannel.EMAIL, NotificationUsage.ALERT,
                    requester.email,
                    Messages.msg(locale, "m.ntf-refund-reported-subject", refund.ref),
                    Messages.msg(locale, "m.ntf-refund-reported-body",
                            nullSafe(refund.delegateName), refund.ref),
                    "advance-refund.reported", refund.ref,
                    Locales.tag(locale), null));
        } catch (RuntimeException e) {
            LOG.warnf(e, "Alerte de report non enfilée pour le reliquat %s", refund.ref);
        }
    }

    private void enqueueSafely(AdvanceRefundEntity refund, Permission right, String excludedEmail,
                               String subjectKey, String bodyKey, String eventType) {
        try {
            UUID tenantId = tenantContext.tenantId();
            if (tenantId == null) return;
            String fallback = preferences.current().language;
            for (UserEntity user : holdersOf(tenantId, right, excludedEmail)) {
                Locale locale = Locales.firstOf(user.locale, fallback);
                queue.enqueue(new NotificationQueue.Request(
                        NotificationChannel.EMAIL, NotificationUsage.ALERT,
                        user.email,
                        Messages.msg(locale, subjectKey, refund.ref),
                        Messages.msg(locale, bodyKey,
                                nullSafe(refund.delegateName),
                                refund.effectiveAmount() == null ? "0"
                                        : refund.effectiveAmount().toPlainString(),
                                refund.ref),
                        eventType, refund.ref,
                        Locales.tag(locale), null));
            }
        } catch (RuntimeException e) {
            LOG.warnf(e, "Alerte non enfilée pour le reliquat %s", refund.ref);
        }
    }

    /**
     * Les comptes actifs porteurs du droit, l'auteur du geste précédent
     * exclu : inviter quelqu'un à faire ce qu'on lui refusera n'a pas de
     * sens.
     */
    private List<UserEntity> holdersOf(UUID tenantId, Permission right, String excludedEmail) {
        List<UserEntity> out = new ArrayList<>();
        for (UserEntity user : users.findByTenant(tenantId, 0, MAX_USERS)) {
            if (user.status != UserStatus.ACTIVE || user.email == null) continue;
            if (excludedEmail != null && excludedEmail.equalsIgnoreCase(user.email)) continue;
            if (!permissions.of(user, tenantId).contains(right)) continue;
            out.add(user);
        }
        return out;
    }

    private static String nullSafe(String v) {
        return v != null ? v : "";
    }
}
