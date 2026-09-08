package com.ntech.cabosse.notification.service;

import com.ntech.cabosse.permission.entity.Permission;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

/**
 * Le catalogue des événements notifiables, auto-descriptif comme les
 * moteurs d'envoi : ajouter un événement ici suffit pour qu'il
 * apparaisse à l'écran de réglage avec ses défauts.
 */
@ApplicationScoped
public class NotificationEventCatalog {

    private static final List<NotificationEventSpec> EVENTS = List.of(
            new NotificationEventSpec("collector-advance.pending-approval",
                    "m.ntf-evt-advance-pending",
                    Permission.COLLECTION_ADVANCE_APPROVE, true),
            new NotificationEventSpec("collector-advance.awaiting-disbursement",
                    "m.ntf-evt-advance-approved",
                    Permission.COLLECTION_ADVANCE_DISBURSE, true),
            new NotificationEventSpec("member-credit.awaiting-disbursement",
                    "m.ntf-evt-member-credit-approved",
                    Permission.MEMBER_CREDIT_DISBURSE, true),
            new NotificationEventSpec("advance-refund.pending-approval",
                    "m.ntf-evt-refund-pending",
                    Permission.COLLECTION_ADVANCE_APPROVE, true),
            new NotificationEventSpec("advance-refund.awaiting-payment",
                    "m.ntf-evt-refund-approved",
                    Permission.COLLECTION_ADVANCE_DISBURSE, true),
            new NotificationEventSpec("collector-advance.execution-requested",
                    "m.ntf-evt-advance-execution",
                    Permission.COLLECTION_ADVANCE_DISBURSE, true),
            new NotificationEventSpec("member-credit.execution-requested",
                    "m.ntf-evt-credit-execution",
                    Permission.MEMBER_CREDIT_DISBURSE, true),
            // Le report s'adresse à celle qui a demandé : audience figée.
            new NotificationEventSpec("advance-refund.reported",
                    "m.ntf-evt-refund-reported",
                    null, false)
    );

    public List<NotificationEventSpec> all() {
        return EVENTS;
    }

    public Optional<NotificationEventSpec> find(String code) {
        return EVENTS.stream().filter(e -> e.code().equals(code)).findFirst();
    }
}
