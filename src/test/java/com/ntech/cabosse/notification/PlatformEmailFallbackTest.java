package com.ntech.cabosse.notification;

import com.ntech.cabosse.notification.entity.NotificationChannel;
import com.ntech.cabosse.notification.entity.NotificationUsage;
import com.ntech.cabosse.notification.service.PlatformEmailFallback;
import com.ntech.cabosse.notification.service.ProviderResolver;
import com.ntech.cabosse.settings.service.PlatformSettingsService;
import com.ntech.cabosse.test.AbstractIntegrationTest;
import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le serveur qui poste les invitations sert aussi la file de notification.
 *
 * <p>Deux chemins d'envoi coexistaient sans se connaître. Une alerte
 * enfilée restait en file indéfiniment, faute d'un fournisseur déclaré,
 * alors qu'un serveur parfaitement configuré postait déjà les invitations
 * à côté. Question de l'utilisateur, le 31/08/2026 : « quand on invite un
 * nouveau membre on reçoit bien un mail, alors pourquoi les notifications
 * ne suivent pas ce chemin ? »</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class PlatformEmailFallbackTest extends AbstractIntegrationTest {

    @Inject PlatformEmailFallback fallback;
    @Inject ProviderResolver resolver;
    @Inject PlatformSettingsService settings;

    private void emailSettings(Map<String, String> values) {
        settings.writeSection("email", values, Set.of("password"), "test");
        settings.invalidateAll();
    }

    @AfterEach
    void clearSettings() {
        settings.writeSection("email", Map.of(), Set.of(), "test");
        settings.invalidateAll();
    }

    @Test
    void the_configured_server_becomes_a_provider_without_being_declared_twice() {
        emailSettings(Map.of(
                "from", "coop@neiba-technologies.com",
                "host", "smtp.example.ci",
                "port", "587",
                "username", "coop",
                "password", "secret",
                "startTls", "REQUIRED",
                // La simulation est active par configuration en test : sans
                // ce réglage explicite, le repli refuserait de servir, et
                // il aurait raison.
                "mockMode", "false"));

        // Le moteur attend exactement ce que les réglages détiennent : rien
        // ne justifie de le ressaisir sous une autre forme.
        var provider = fallback.provider();
        assertThat(provider).isPresent();
        assertThat(provider.get().channel).isEqualTo(NotificationChannel.EMAIL);
        assertThat(provider.get().params).containsEntry("host", "smtp.example.ci");
        assertThat(provider.get().params).containsEntry("from", "coop@neiba-technologies.com");
    }

    @Test
    void the_email_channel_stops_being_dead() {
        emailSettings(Map.of(
                "from", "coop@neiba-technologies.com",
                "host", "smtp.example.ci",
                "port", "587",
                "mockMode", "false"));

        // C'est ce que le relais interroge avant de drainer. Sans cela il
        // s'arrêtait sans rien tenter, et la file restait pleine à côté
        // d'un serveur configuré.
        assertThat(resolver.channelHasActiveProvider(NotificationChannel.EMAIL)).isTrue();
        assertThat(resolver.resolve(NotificationChannel.EMAIL, NotificationUsage.ALERT))
                .hasSize(1);
    }

    @Test
    void incomplete_settings_produce_nothing() {
        emailSettings(Map.of("host", "smtp.example.ci", "mockMode", "false"));

        // Un canal absent vaut mieux qu'un envoi qui échouera cinq fois
        // avant d'être abandonné.
        assertThat(fallback.provider()).isEmpty();
    }

    @Test
    void simulation_mode_sends_nothing() {
        emailSettings(Map.of(
                "from", "coop@neiba-technologies.com",
                "host", "smtp.example.ci",
                "port", "587",
                "mockMode", "true"));

        // En simulation, l'envoi doit rester en file plutôt que d'être
        // compté comme parti.
        assertThat(fallback.provider()).isEmpty();
    }

    @Test
    void the_fallback_never_overrides_a_declared_provider() {
        emailSettings(Map.of(
                "from", "socle@neiba-technologies.com",
                "host", "smtp.socle.ci",
                "port", "587",
                "mockMode", "false"));

        var usable = resolver.resolve(NotificationChannel.EMAIL, NotificationUsage.ALERT);

        // Sans fournisseur déclaré, le repli sert. C'est l'ordre qui compte
        // pour la suite : dès qu'une structure déclarera le sien, il devra
        // passer devant, et ce repli ne doit jamais s'y ajouter.
        assertThat(usable).hasSize(1);
        assertThat(usable.get(0).provider().label).isEqualTo("Serveur de la plateforme");
    }
}
