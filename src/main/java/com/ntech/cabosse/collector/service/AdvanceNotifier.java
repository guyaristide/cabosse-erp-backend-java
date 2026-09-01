package com.ntech.cabosse.collector.service;

import com.ntech.cabosse.collector.entity.CollectorAdvanceEntity;
import com.ntech.cabosse.notification.entity.NotificationChannel;
import com.ntech.cabosse.notification.entity.NotificationUsage;
import com.ntech.cabosse.notification.service.NotificationQueue;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.permission.service.PermissionResolver;
import com.ntech.cabosse.tenant.service.TenantPreferencesLookup;
import com.ntech.cabosse.shared.i18n.Locales;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.entity.UserStatus;
import com.ntech.cabosse.user.repository.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Prévient ceux qui peuvent approuver qu'une avance les attend.
 *
 * <p>La file d'envoi existait depuis le 21/08/2026 sans qu'aucun événement
 * métier ne l'alimente : le socle était posé, les alertes ne l'étaient pas.
 * L'expert filière l'a constaté à l'usage, en demandant si un message
 * partait à la validation. Aucun ne partait.</p>
 *
 * <p>La demande d'avance est le premier cas à câbler parce qu'elle réunit
 * les deux conditions qui rendent une alerte utile : un destinataire que
 * l'on sait désigner, celui qui détient le droit d'approuver, et une
 * attente réelle, le délégué étant sans fonds tant que personne n'a
 * décidé.</p>
 */
@ApplicationScoped
public class AdvanceNotifier {

    private static final Logger LOG = Logger.getLogger(AdvanceNotifier.class);

    /** Au-delà, on cesse de parcourir : une structure n'a pas mille comptes. */
    private static final int MAX_USERS = 500;

    @Inject NotificationQueue queue;
    @Inject UserRepository users;
    @Inject PermissionResolver permissions;
    @Inject TenantPreferencesLookup preferences;
    @Inject TenantContext tenantContext;

    /**
     * Une demande vient d'être déposée et attend une décision.
     *
     * <p>L'envoi ne fait jamais échouer la demande. Une avance enregistrée
     * dont l'alerte n'est pas partie reste une avance enregistrée ; l'
     * inverse ferait perdre une saisie pour une raison qui ne regarde pas
     * celui qui l'a faite.</p>
     */
    public void advanceAwaitsApproval(CollectorAdvanceEntity advance) {
        try {
            notifyApprovers(advance);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Alerte d'approbation non enfilée pour l'avance %s", advance.ref);
        }
    }

    private void notifyApprovers(CollectorAdvanceEntity advance) {
        UUID tenantId = tenantContext.tenantId();
        if (tenantId == null) return;

        String fallback = preferences.current().language;

        for (UserEntity user : approvers(tenantId, advance)) {
            Locale locale = Locales.firstOf(user.locale, fallback);
            String subject = Messages.msg(locale, "m.ntf-advance-pending-subject", advance.ref);
            String body = Messages.msg(locale, "m.ntf-advance-pending-body",
                    advance.delegateName == null ? "" : advance.delegateName,
                    advance.advanceAmountFcfa == null ? "0" : advance.advanceAmountFcfa.toPlainString(),
                    advance.ref);
            queue.enqueue(new NotificationQueue.Request(
                    NotificationChannel.EMAIL, NotificationUsage.ALERT,
                    user.email, subject, body,
                    "collector-advance.pending-approval", advance.ref,
                    Locales.tag(locale), null));
        }
    }

    /**
     * Les comptes actifs qui peuvent approuver, sauf celui qui a déposé.
     *
     * <p>Le demandeur n'approuve pas sa propre demande : lui écrire pour
     * l'inviter à décider n'aurait aucun sens, et la règle des deux paires
     * d'yeux le lui refuserait de toute façon.</p>
     */
    private List<UserEntity> approvers(UUID tenantId, CollectorAdvanceEntity advance) {
        return users.findByTenant(tenantId, 0, MAX_USERS).stream()
                .filter(u -> u.status == UserStatus.ACTIVE)
                .filter(u -> u.email != null && !u.email.isBlank())
                .filter(u -> !u.id.equals(advance.createdBy))
                .filter(u -> permissions.of(u, tenantId).contains(Permission.COLLECTION_ADVANCE_APPROVE))
                .toList();
    }
}
