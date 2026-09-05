package com.ntech.cabosse.notification.service;

import com.ntech.cabosse.notification.dto.DeliveryResponseDto;
import com.ntech.cabosse.notification.entity.DeliveryStatus;
import com.ntech.cabosse.notification.entity.NotificationChannel;
import com.ntech.cabosse.notification.repository.NotificationDeliveryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;

/**
 * Lecture du journal des envois du tenant.
 *
 * <p>Le contrôleur interrogeait le repository lui-même. Règle de la
 * maison, rappelée le 04/09/2026 : un contrôleur ne touche jamais un
 * repository, seul un service y accède. Même une lecture simple passe par
 * ici, pour que le jour où elle cesse d'être simple, filtre de droits ou
 * masquage d'un champ, la règle ait déjà sa place.</p>
 */
@ApplicationScoped
public class NotificationJournalService {

    @Inject NotificationDeliveryRepository deliveries;

    public List<DeliveryResponseDto> search(NotificationChannel channel, DeliveryStatus status,
                                            Instant from, Instant to, int limit, int skip) {
        return deliveries.search(channel, status, from, to, limit, skip).stream()
                .map(DeliveryResponseDto::from)
                .toList();
    }
}
