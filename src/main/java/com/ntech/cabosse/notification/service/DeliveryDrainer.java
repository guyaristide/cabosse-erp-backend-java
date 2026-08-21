package com.ntech.cabosse.notification.service;

import com.ntech.cabosse.notification.engine.NotificationHttpClient;
import com.ntech.cabosse.notification.engine.SendOutcome;
import com.ntech.cabosse.notification.engine.SendRequest;
import com.ntech.cabosse.notification.entity.NotificationChannel;
import com.ntech.cabosse.notification.entity.NotificationDeliveryEntity;
import com.ntech.cabosse.notification.repository.NotificationDeliveryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Vide la file d'un canal pour le tenant courant : prend une ligne,
 * l'envoie, consigne le résultat.
 *
 * <p>L'envoi a lieu <strong>hors de toute transaction</strong> et la
 * prise est atomique (voir le dépôt) : c'est ce qui empêche deux
 * instances d'émettre deux fois le même message, sans avoir besoin du
 * marquage transactionnel séparé qu'imposerait une base relationnelle.</p>
 *
 * <p>Bascule sur la passerelle suivante en cas de refus : une clé
 * révoquée chez l'opérateur principal ne doit pas bloquer les envois si
 * un second compte est configuré.</p>
 */
@ApplicationScoped
public class DeliveryDrainer {

    @Inject NotificationDeliveryRepository deliveries;
    @Inject ProviderResolver resolver;
    @Inject Logger log;

    /** Résultat d'un passage, pour la trace et les tests. */
    public record DrainReport(int sent, int retried, int failed, int expired) {
        static DrainReport empty() { return new DrainReport(0, 0, 0, 0); }
        public int handled() { return sent + retried + failed; }
    }

    /**
     * Draine au plus {@link DeliveryPolicy#BATCH_SIZE} lignes du canal.
     * Ne consomme aucune tentative si le canal n'a pas de passerelle
     * configurée : une file qui attend une configuration n'est pas une
     * file en échec, et brûler ses tentatives condamnerait des messages
     * qui n'ont jamais eu leur chance.
     */
    public DrainReport drain(NotificationChannel channel) {
        Instant now = Instant.now();
        int expired = (int) deliveries.expireOverdue(now);

        if (!resolver.channelHasActiveProvider(channel)) {
            return new DrainReport(0, 0, 0, expired);
        }
        if (!deliveries.hasWork(channel, now)) {
            return new DrainReport(0, 0, 0, expired);
        }

        int sent = 0;
        int retried = 0;
        int failed = 0;
        for (int i = 0; i < DeliveryPolicy.BATCH_SIZE; i++) {
            Optional<NotificationDeliveryEntity> claimed =
                    deliveries.claimNext(channel, Instant.now(), DeliveryPolicy.RECLAIM_AFTER);
            if (claimed.isEmpty()) break;

            switch (deliverOne(claimed.get())) {
                case SENT -> sent++;
                case RETRY -> retried++;
                case FAILED -> failed++;
            }
        }
        return new DrainReport(sent, retried, failed, expired);
    }

    private enum Conclusion { SENT, RETRY, FAILED }

    private Conclusion deliverOne(NotificationDeliveryEntity delivery) {
        List<ResolvedProvider> candidates = resolver.resolve(delivery.channel, delivery.usage);
        if (candidates.isEmpty()) {
            // Le canal a un fournisseur actif, mais aucun ne sert cet usage
            // ou aucun n'est utilisable. C'est un défaut de configuration :
            // on réarme sans consommer la ligne définitivement.
            return conclude(delivery, null,
                    "Aucune passerelle utilisable pour l'usage " + delivery.usage + ".");
        }

        SendRequest request = new SendRequest(
                delivery.channel, delivery.target, delivery.subject, delivery.body);

        String lastReason = null;
        String lastProvider = null;
        for (ResolvedProvider candidate : candidates) {
            lastProvider = candidate.engineCode();
            SendOutcome outcome;
            try {
                outcome = candidate.engine().send(request, candidate.params());
            } catch (Exception e) {
                // Un moteur ne devrait pas lever pour un refus d'opérateur,
                // mais l'imprévu ne doit jamais tuer le relais.
                outcome = SendOutcome.failed(NotificationHttpClient.describe(e));
            }
            if (outcome.success()) {
                deliveries.markSent(delivery.id, candidate.engineCode(),
                        outcome.providerMessageId(), Instant.now());
                return Conclusion.SENT;
            }
            lastReason = outcome.failureReason();
            log.warnf("Envoi %s refusé par « %s » : %s",
                    delivery.channel, candidate.label(), lastReason);
        }
        return conclude(delivery, lastProvider, lastReason);
    }

    /** Réarme ou abandonne, selon les tentatives déjà consommées. */
    private Conclusion conclude(NotificationDeliveryEntity delivery,
                                 String providerCode, String reason) {
        String truncated = NotificationHttpClient.truncate(
                reason != null ? reason : "Échec sans motif rendu par la passerelle.");
        Instant now = Instant.now();
        if (delivery.attempts >= DeliveryPolicy.MAX_ATTEMPTS) {
            deliveries.markFailed(delivery.id, providerCode, truncated, now);
            log.errorf("Notification abandonnée après %d tentatives (canal=%s, événement=%s) : %s",
                    delivery.attempts, delivery.channel, delivery.eventType, truncated);
            return Conclusion.FAILED;
        }
        deliveries.markRetry(delivery.id, providerCode, truncated,
                now.plus(DeliveryPolicy.backoffAfter(delivery.attempts)), now);
        return Conclusion.RETRY;
    }
}
