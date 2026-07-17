package com.ntech.cabosse.auth.service;

import com.ntech.cabosse.shared.exception.UnauthorizedException;
import com.ntech.cabosse.test.AbstractIntegrationTest;
import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reproduit la course vue en production (2026-07-06) : un logout et un
 * refresh simultanés sur le même token provoquaient un
 * {@code WriteConflict} Mongo (erreur 112, transaction multi-documents)
 * remonté en 500. Le correctif remplace les read-modify-replace
 * transactionnels par des {@code updateOne} conditionnels atomiques.
 *
 * <p>Garanties vérifiées sous concurrence :</p>
 * <ol>
 *   <li>Aucune exception technique ne s'échappe — les perdants reçoivent
 *       au pire une {@link UnauthorizedException} (métier, mappée 401),
 *       jamais un {@code MongoCommandException} (mappé 500).</li>
 *   <li>Au plus UNE rotation réussit par token : les rotations
 *       concurrentes perdantes sont traitées comme un rejeu.</li>
 * </ol>
 *
 * <p>Une course reste probabiliste : on répète plusieurs rounds avec un
 * départ synchronisé (latch) pour maximiser la fenêtre de collision. Le
 * test échouait de façon intermittente avant correctif ; il doit être
 * stable à 100 % après.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class RefreshTokenConcurrencyTest extends AbstractIntegrationTest {

    private static final int ROUNDS = 5;
    private static final int WORKERS = 8;

    @Inject RefreshTokenService service;

    @Test
    void concurrent_logout_and_refresh_on_same_token_never_fail_technically() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                RefreshTokenService.IssuedRefreshToken issued = service.issueNew(
                        UUID.randomUUID(), UUID.randomUUID(), "test-agent", "127.0.0.1");

                CountDownLatch start = new CountDownLatch(1);
                List<Future<String>> futures = new ArrayList<>();
                for (int i = 0; i < WORKERS; i++) {
                    final boolean doRevoke = i % 2 == 0;
                    futures.add(pool.submit(() -> {
                        start.await();
                        try {
                            if (doRevoke) {
                                service.revoke(issued.secret(), "logout");
                                return "revoked";
                            }
                            service.rotate(issued.secret(), "test-agent", "127.0.0.1");
                            return "rotated";
                        } catch (UnauthorizedException e) {
                            return "unauthorized";
                        }
                    }));
                }
                start.countDown();

                // Toute autre exception (MongoCommandException / WriteConflict…)
                // remonterait ici en ExecutionException et ferait échouer le test.
                List<String> outcomes = new ArrayList<>();
                for (Future<String> f : futures) {
                    outcomes.add(f.get(30, TimeUnit.SECONDS));
                }

                long rotations = outcomes.stream().filter("rotated"::equals).count();
                assertThat(rotations)
                        .as("round %d : une seule rotation peut gagner (outcomes=%s)", round, outcomes)
                        .isLessThanOrEqualTo(1);
                assertThat(outcomes.stream().filter("revoked"::equals).count())
                        .as("round %d : les logouts sont idempotents, aucun ne doit échouer", round)
                        .isEqualTo(WORKERS / 2);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void revoke_is_idempotent_and_blocks_further_rotation() {
        RefreshTokenService.IssuedRefreshToken issued = service.issueNew(
                UUID.randomUUID(), UUID.randomUUID(), "test-agent", "127.0.0.1");

        service.revoke(issued.secret(), "logout");
        service.revoke(issued.secret(), "logout"); // second appel silencieux

        assertThatThrownBy(() -> service.rotate(issued.secret(), "test-agent", "127.0.0.1"))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void replaying_a_rotated_token_revokes_the_whole_family() {
        RefreshTokenService.IssuedRefreshToken issued = service.issueNew(
                UUID.randomUUID(), UUID.randomUUID(), "test-agent", "127.0.0.1");

        RefreshTokenService.RotatedRefresh rotated =
                service.rotate(issued.secret(), "test-agent", "127.0.0.1");

        // Rejeu de l'ancien secret → toute la famille tombe, y compris le
        // token fraîchement émis.
        assertThatThrownBy(() -> service.rotate(issued.secret(), "test-agent", "127.0.0.1"))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> service.rotate(rotated.token().secret(), "test-agent", "127.0.0.1"))
                .isInstanceOf(UnauthorizedException.class);
    }
}
