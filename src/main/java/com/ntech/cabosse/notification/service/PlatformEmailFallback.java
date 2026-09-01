package com.ntech.cabosse.notification.service;

import com.ntech.cabosse.notification.engine.SmtpEngine;
import com.ntech.cabosse.notification.entity.NotificationChannel;
import com.ntech.cabosse.notification.entity.NotificationProviderEntity;
import com.ntech.cabosse.notification.entity.NotificationUsage;
import com.ntech.cabosse.notification.entity.ProviderUsage;
import com.ntech.cabosse.settings.service.PlatformSettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Le serveur d'envoi qui poste déjà les invitations, mis à disposition de
 * la file de notification.
 *
 * <p>Deux chemins d'envoi coexistaient sans se connaître. Le premier, celui
 * du mailer de la plateforme, lit sa configuration dans les réglages du
 * back-office et poste les invitations et les courriels d'assistance. Le
 * second, la file durable, offre relances, canaux multiples et journal,
 * mais ses fournisseurs n'ont aucun écran et personne n'en a donc jamais
 * déclaré. Une alerte enfilée restait en file indéfiniment, faute d'un
 * canal, alors qu'un serveur parfaitement configuré se trouvait à côté.</p>
 *
 * <p>Le moteur SMTP de la file attend exactement les paramètres que ces
 * réglages détiennent : hôte, port, expéditeur, identifiant, mot de passe,
 * chiffrement. Le fournisseur s'en dérive donc, plutôt que de demander une
 * seconde saisie de la même chose.</p>
 *
 * <p>Ce fournisseur <b>n'est jamais écrit en base</b>. Le recopier ferait
 * diverger les deux configurations dès la première modification des
 * réglages, et l'on enverrait par un serveur que plus personne ne croit
 * utilisé.</p>
 */
@ApplicationScoped
public class PlatformEmailFallback {

    /** Identifiant fixe : il désigne toujours la même origine dans le journal. */
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-00000000e5a1");

    private static final String SECTION = "email";

    @Inject PlatformSettingsService settings;
    @Inject Config config;
    @Inject Logger log;

    /**
     * Fournisseur dérivé des réglages, ou rien s'ils ne suffisent pas.
     *
     * <p>Le mode simulation ne produit aucun fournisseur : l'envoi doit
     * rester en file plutôt que d'être compté comme parti. Une
     * configuration incomplète non plus, un canal absent valant mieux
     * qu'un envoi qui échouera cinq fois avant d'être abandonné.</p>
     */
    public Optional<NotificationProviderEntity> provider() {
        Map<String, String> db = settings.readFromDb(SECTION);

        if (parseBool(effective(db.get("mockMode"), "quarkus.mailer.mock", "false"))) {
            return Optional.empty();
        }

        String from = effective(db.get("from"), "quarkus.mailer.from", null);
        String host = effective(db.get("host"), "quarkus.mailer.host", null);
        String port = effective(db.get("port"), "quarkus.mailer.port", null);
        if (isBlank(from) || isBlank(host) || isBlank(port)) {
            log.debugf("Aucun repli d'envoi : réglages de courriel incomplets "
                    + "(expéditeur=%s, hôte=%s, port=%s).", from, host, port);
            return Optional.empty();
        }

        Map<String, String> params = new HashMap<>();
        params.put("from", from);
        params.put("host", host);
        params.put("port", port);
        put(params, "username", effective(db.get("username"), "quarkus.mailer.username", null));
        put(params, "password", effective(db.get("password"), "quarkus.mailer.password", null));
        params.put("startTls",
                effective(db.get("startTls"), "quarkus.mailer.start-tls", "REQUIRED"));

        NotificationProviderEntity e = new NotificationProviderEntity();
        e.id = ID;
        e.engineCode = SmtpEngine.CODE;
        e.label = "Serveur de la plateforme";
        e.channel = NotificationChannel.EMAIL;
        e.active = true;
        e.params = params;
        // Les valeurs sortent déjà en clair des réglages : les déclarer
        // secrètes ferait tenter un déchiffrement sur du texte lisible.
        e.secretKeys = java.util.Set.of();
        // Tous les usages, et en dernier recours : un fournisseur déclaré
        // doit toujours l'emporter sur celui-ci.
        e.usages = List.of(
                new ProviderUsage(NotificationUsage.TRANSACTIONAL, Integer.MAX_VALUE),
                new ProviderUsage(NotificationUsage.ALERT, Integer.MAX_VALUE));
        return Optional.of(e);
    }

    private void put(Map<String, String> params, String key, String value) {
        if (!isBlank(value)) params.put(key, value);
    }

    private String effective(String fromDb, String configKey, String fallback) {
        if (!isBlank(fromDb)) return fromDb;
        return config.getOptionalValue(configKey, String.class).orElse(fallback);
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    private static boolean parseBool(String v) {
        return v != null && ("true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim()));
    }
}
