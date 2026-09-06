package com.ntech.cabosse.notification.service;

import com.ntech.cabosse.notification.dto.InboxNotificationDto;
import com.ntech.cabosse.notification.repository.NotificationDeliveryRepository;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * La boîte de réception de l'utilisateur courant (canal de l'application).
 *
 * <p>Strictement personnelle : chaque lecture et chaque marquage sont
 * filtrés sur l'identifiant du lecteur, jamais sur un paramètre. Une
 * notification ne se lit ni ne se marque pour quelqu'un d'autre.</p>
 */
@ApplicationScoped
public class InboxService {

    @Inject NotificationDeliveryRepository deliveries;
    @Inject TenantContext tenantContext;

    private String me() {
        UUID userId = tenantContext.userId();
        return userId != null ? userId.toString() : "";
    }

    public Pagination<InboxNotificationDto> page(PageRequest pr) {
        String target = me();
        List<InboxNotificationDto> items = deliveries
                .listInbox(target, pr.skip(), pr.perPage())
                .stream().map(InboxNotificationDto::from).toList();
        return Pagination.of(deliveries.countInbox(target), pr,
                new String[]{"createdAt"}, "desc", Map.of(), items);
    }

    public long unreadCount() {
        return deliveries.countUnread(me());
    }

    public boolean markRead(UUID id) {
        return deliveries.markRead(id, me(), Instant.now());
    }

    public long markAllRead() {
        return deliveries.markAllRead(me(), Instant.now());
    }
}
