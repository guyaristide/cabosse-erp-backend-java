package com.ntech.cabosse.notification.engine;

import com.ntech.cabosse.notification.entity.NotificationChannel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Passerelle SMS factice : journalise au lieu d'émettre. Permet de
 * dérouler un parcours complet en développement et en intégration sans
 * consommer de crédits ni dépendre d'un opérateur, y compris pour les
 * futurs codes à usage unique.
 *
 * <p>Elle est indispensable et non décorative : un système de SMS sans
 * mode simulé ne se teste pas, et c'est un manque avéré des projets dont
 * ce socle s'inspire.</p>
 *
 * <p>Elle reste un moteur comme un autre, donc inutilisable tant qu'un
 * administrateur ne l'a pas explicitement configurée.</p>
 */
@ApplicationScoped
public class MockSmsEngine implements ProviderEnginePort {

    public static final String CODE = "MOCK_SMS";

    /** Derniers messages simulés, pour l'inspection en test. */
    private final List<String> sent = new CopyOnWriteArrayList<>();

    @Inject Logger log;

    @Override public String code() { return CODE; }
    @Override public String label() { return "SMS simulé (développement)"; }
    @Override public NotificationChannel channel() { return NotificationChannel.SMS; }

    @Override
    public List<EngineParam> declaredParams() {
        return List.of(new EngineParam("senderName", "m.ntf-p-mock-sender-name", false, false,
                "m.ntf-p-mock-sender-name-help"));
    }

    @Override
    public SendOutcome send(SendRequest request, Map<String, String> params) {
        sent.add(request.target() + " :: " + request.body());
        log.infof("SMS simulé vers %s : %s", request.target(), request.body());
        return SendOutcome.sent("mock-" + UUID.randomUUID());
    }

    public List<String> sentMessages() {
        return new ArrayList<>(sent);
    }

    public void clear() {
        sent.clear();
    }
}
