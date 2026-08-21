package com.ntech.cabosse.notification.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.ntech.cabosse.notification.entity.DeliveryStatus;
import com.ntech.cabosse.notification.entity.NotificationChannel;
import com.ntech.cabosse.notification.entity.NotificationDeliveryEntity;
import com.ntech.cabosse.notification.entity.NotificationUsage;
import com.ntech.cabosse.notification.repository.NotificationDeliveryRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Chemin d'écriture <strong>unique</strong> vers la file d'envoi.
 *
 * <p>Rien d'autre n'insère dans {@code notification_deliveries} : c'est
 * la règle qui protège l'invariant de la file. L'incident d'outbox
 * observé sur un projet voisin ne venait pas du principe de la file mais
 * d'un second chemin d'écriture ouvert plus tard.</p>
 *
 * <p>L'enfilage est court et ne fait aucun appel réseau : le message est
 * déjà rendu, on l'écrit et on rend la main. L'envoi appartient au relais.
 * Un service métier n'attend donc jamais une passerelle, et un échec
 * d'envoi ne peut pas faire échouer l'opération qui l'a déclenché.</p>
 */
@ApplicationScoped
public class NotificationQueue {

    @Inject NotificationDeliveryRepository deliveries;
    @Inject Logger log;

    /**
     * Demande d'envoi. {@code subject} n'a de sens que pour le courriel ;
     * {@code timeToLive} peut raccourcir la durée de vie d'une alerte dont
     * l'objet se périme vite.
     */
    public record Request(NotificationChannel channel,
                          NotificationUsage usage,
                          String target,
                          String subject,
                          String body,
                          String eventType,
                          String subjectRef,
                          String locale,
                          Duration timeToLive) {

        public static Request email(String target, String subject, String body,
                                     String eventType, NotificationUsage usage) {
            return new Request(NotificationChannel.EMAIL, usage, target, subject, body,
                    eventType, null, null, null);
        }

        public static Request sms(String target, String body,
                                   String eventType, NotificationUsage usage) {
            return new Request(NotificationChannel.SMS, usage, target, null, body,
                    eventType, null, null, null);
        }
    }

    /**
     * Enfile une demande et rend l'identifiant de la ligne créée.
     *
     * @throws BusinessException si la demande est inexploitable (cible ou
     *         corps absent) : mieux vaut le dire à l'appelant que d'écrire
     *         une ligne qui échouera cinq fois avant d'être abandonnée.
     */
    public UUID enqueue(Request request) {
        if (request == null) throw new BusinessException("Demande de notification requise.");
        if (request.channel() == null) throw new BusinessException("Canal requis.");
        if (request.target() == null || request.target().isBlank()) {
            throw new BusinessException("Destinataire requis.");
        }
        if (request.body() == null || request.body().isBlank()) {
            throw new BusinessException("Corps du message requis.");
        }

        Instant now = Instant.now();
        NotificationDeliveryEntity e = new NotificationDeliveryEntity();
        e.id = UuidCreator.getTimeOrderedEpoch();
        e.channel = request.channel();
        e.usage = request.usage() != null ? request.usage() : NotificationUsage.ALERT;
        e.target = request.target().trim();
        e.subject = request.subject();
        e.body = request.body();
        e.eventType = request.eventType();
        e.subjectRef = request.subjectRef();
        e.locale = request.locale();
        e.status = DeliveryStatus.PENDING;
        e.attempts = 0;
        e.nextAttemptAt = now;
        e.expiresAt = now.plus(request.timeToLive() != null
                ? request.timeToLive() : DeliveryPolicy.DEFAULT_TIME_TO_LIVE);
        e.createdAt = now;
        e.updatedAt = now;

        deliveries.insert(e);
        log.debugf("Notification enfilée : canal=%s usage=%s événement=%s",
                e.channel, e.usage, e.eventType);
        return e.id;
    }
}
