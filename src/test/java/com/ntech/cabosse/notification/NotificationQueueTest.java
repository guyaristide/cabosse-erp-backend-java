package com.ntech.cabosse.notification;

import com.ntech.cabosse.notification.engine.MockSmsEngine;
import com.ntech.cabosse.notification.entity.DeliveryStatus;
import com.ntech.cabosse.notification.entity.NotificationChannel;
import com.ntech.cabosse.notification.entity.NotificationProviderEntity;
import com.ntech.cabosse.notification.entity.NotificationUsage;
import com.ntech.cabosse.notification.entity.ProviderUsage;
import com.ntech.cabosse.notification.repository.NotificationDeliveryRepository;
import com.ntech.cabosse.notification.repository.NotificationProviderRepository;
import com.ntech.cabosse.notification.service.DeliveryDrainer;
import com.ntech.cabosse.notification.service.DeliveryPolicy;
import com.ntech.cabosse.notification.service.NotificationQueue;
import com.ntech.cabosse.shared.tenant.TenantAwareExecutor;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.test.AbstractIntegrationTest;
import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import com.ntech.cabosse.test.TestFixtures;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La file d'envoi : une notification enfilée survit au redémarrage, part
 * une seule fois même si plusieurs instances drainent, et laisse une
 * trace exploitable quand elle échoue.
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class NotificationQueueTest extends AbstractIntegrationTest {

    @Inject NotificationQueue queue;
    @Inject DeliveryDrainer drainer;
    @Inject NotificationDeliveryRepository deliveries;
    @Inject NotificationProviderRepository providers;
    @Inject TenantAwareExecutor executor;
    @Inject MockSmsEngine mockSms;

    private TenantEntity tenant;

    @BeforeEach
    void prepare() {
        mockSms.clear();
        tenant = fixtures.createActiveTenant(
                "coop-file-" + TestFixtures.randomSlugSuffix(), "Coopérative File");
        for (NotificationProviderEntity existing : providers.listAll()) {
            providers.delete(existing.id);
        }
    }

    private void configureMockSms(NotificationUsage usage) {
        NotificationProviderEntity p = new NotificationProviderEntity();
        p.id = UUID.randomUUID();
        p.engineCode = MockSmsEngine.CODE;
        p.label = "Simulateur";
        p.channel = NotificationChannel.SMS;
        p.active = true;
        p.usages = new ArrayList<>(List.of(new ProviderUsage(usage, 0)));
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
        providers.insert(p);
    }

    /** Exécute dans le contexte du tenant, comme le fait le relais. */
    private <T> T inTenant(java.util.function.Supplier<T> block) {
        List<T> holder = new ArrayList<>(1);
        executor.runForTenant(tenant.id, tenant.databaseName, () -> holder.add(block.get()));
        return holder.get(0);
    }

    @Test
    void une_notification_enfilee_part_puis_devient_tracable() {
        configureMockSms(NotificationUsage.TRANSACTIONAL);

        UUID id = inTenant(() -> queue.enqueue(NotificationQueue.Request.sms(
                "+2250700000001", "Votre code est 123456",
                "OTP_REQUESTED", NotificationUsage.TRANSACTIONAL, Locale.FRENCH)));

        // Enfilée : elle existe avant même qu'une passerelle ait été
        // sollicitée. C'est ce qui la rend increvable à un redémarrage.
        assertEquals(DeliveryStatus.PENDING,
                inTenant(() -> deliveries.findById(id).orElseThrow()).status);

        DeliveryDrainer.DrainReport report =
                inTenant(() -> drainer.drain(NotificationChannel.SMS));
        assertEquals(1, report.sent());

        var after = inTenant(() -> deliveries.findById(id).orElseThrow());
        assertEquals(DeliveryStatus.SENT, after.status);
        assertEquals(MockSmsEngine.CODE, after.providerCode);
        assertNotNull(after.providerMessageId);
        assertNotNull(after.sentAt);
        assertEquals(1, after.attempts);
        assertTrue(mockSms.sentMessages().stream()
                .anyMatch(m -> m.contains("+2250700000001")));
    }

    @Test
    void sans_passerelle_configuree_aucune_tentative_n_est_consommee() {
        UUID id = inTenant(() -> queue.enqueue(NotificationQueue.Request.sms(
                "+2250700000002", "Message en attente de configuration",
                "TEST", NotificationUsage.ALERT, Locale.FRENCH)));

        DeliveryDrainer.DrainReport report =
                inTenant(() -> drainer.drain(NotificationChannel.SMS));
        assertEquals(0, report.handled());

        // Une file qui attend sa configuration n'est pas une file en échec :
        // brûler ses tentatives condamnerait des messages qui n'ont jamais
        // eu leur chance.
        var delivery = inTenant(() -> deliveries.findById(id).orElseThrow());
        assertEquals(DeliveryStatus.PENDING, delivery.status);
        assertEquals(0, delivery.attempts);
    }

    @Test
    void un_usage_non_servi_n_envoie_pas_par_une_autre_passerelle() {
        configureMockSms(NotificationUsage.TRANSACTIONAL);

        UUID id = inTenant(() -> queue.enqueue(NotificationQueue.Request.sms(
                "+2250700000003", "Rappel de stock bas", "STOCK_LOW", NotificationUsage.ALERT, Locale.FRENCH)));

        inTenant(() -> drainer.drain(NotificationChannel.SMS));

        // La passerelle ne sert que le transactionnel : l'alerte attend
        // une configuration, elle n'emprunte pas un canal non prévu.
        var delivery = inTenant(() -> deliveries.findById(id).orElseThrow());
        assertEquals(DeliveryStatus.PENDING, delivery.status);
        assertNotNull(delivery.lastError);
        assertTrue(mockSms.sentMessages().isEmpty());
    }

    @Test
    void une_ligne_perimee_ne_part_plus() {
        configureMockSms(NotificationUsage.ALERT);

        UUID id = inTenant(() -> queue.enqueue(new NotificationQueue.Request(
                NotificationChannel.SMS, NotificationUsage.ALERT, "+2250700000004",
                null, "Alerte déjà obsolète", "STOCK_LOW", null, null,
                Duration.ofMillis(1))));

        try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        inTenant(() -> drainer.drain(NotificationChannel.SMS));

        // Une file rallumée après une panne ne doit pas déverser des
        // alertes dont l'objet n'existe plus.
        assertEquals(DeliveryStatus.EXPIRED,
                inTenant(() -> deliveries.findById(id).orElseThrow()).status);
        assertTrue(mockSms.sentMessages().isEmpty());
    }

    @Test
    void deux_drainages_concurrents_n_envoient_pas_deux_fois() throws Exception {
        configureMockSms(NotificationUsage.ALERT);

        int messages = 12;
        for (int i = 0; i < messages; i++) {
            int n = i;
            inTenant(() -> queue.enqueue(NotificationQueue.Request.sms(
                    "+22507000001" + String.format("%02d", n),
                    "Message " + n, "TEST", NotificationUsage.ALERT, Locale.FRENCH)));
        }

        // Quatre relais en parallèle, comme quatre instances derrière un
        // répartiteur : la prise atomique doit rendre l'envoi unique.
        int workers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger totalSent = new AtomicInteger();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                totalSent.addAndGet(inTenant(() -> drainer.drain(NotificationChannel.SMS)).sent());
                return null;
            }));
        }
        start.countDown();
        for (var f : futures) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(messages, totalSent.get(),
                "Chaque message doit être compté une seule fois par un seul relais");
        assertEquals(messages, mockSms.sentMessages().size(),
                "Aucun message ne doit être émis deux fois");
        assertEquals(messages, inTenant(() ->
                deliveries.count(NotificationChannel.SMS, DeliveryStatus.SENT)));
    }

    @Test
    void le_plafond_de_tentatives_vient_d_une_seule_constante() {
        // La règle est écrite une fois : sur un projet voisin, la même
        // valeur vivait à deux endroits et avait fini par diverger.
        assertTrue(DeliveryPolicy.MAX_ATTEMPTS > 0);
        assertTrue(DeliveryPolicy.backoffAfter(1)
                .compareTo(DeliveryPolicy.backoffAfter(3)) < 0,
                "Le retrait entre tentatives doit s'allonger");
        assertTrue(DeliveryPolicy.backoffAfter(100).toMinutes() <= 60,
                "Le retrait doit rester borné");
    }

    @Test
    void une_demande_sans_destinataire_est_refusee_a_l_enfilage() {
        org.junit.jupiter.api.Assertions.assertThrows(
                com.ntech.cabosse.shared.exception.BusinessException.class,
                () -> inTenant(() -> queue.enqueue(NotificationQueue.Request.sms(
                        "  ", "Corps", "TEST", NotificationUsage.ALERT, Locale.FRENCH))));
    }
}
