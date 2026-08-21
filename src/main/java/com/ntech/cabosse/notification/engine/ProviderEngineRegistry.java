package com.ntech.cabosse.notification.engine;

import com.ntech.cabosse.notification.entity.NotificationChannel;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Recense les moteurs présents dans la livraison, par découverte CDI.
 *
 * <p>C'est ce registre qui répond à « ce fournisseur peut-il émettre » :
 * un fournisseur enregistré dont le moteur a disparu de la livraison est
 * actif en base et incapable d'envoyer. Sans ce contrôle, la passerelle
 * paraît en service et le silence est incompréhensible.</p>
 */
@ApplicationScoped
public class ProviderEngineRegistry {

    @Inject Instance<ProviderEnginePort> discovered;
    @Inject Logger log;

    private final Map<String, ProviderEnginePort> byCode = new LinkedHashMap<>();

    @PostConstruct
    void index() {
        List<ProviderEnginePort> engines = new ArrayList<>();
        discovered.forEach(engines::add);
        engines.sort(Comparator.comparing(ProviderEnginePort::code));
        for (ProviderEnginePort engine : engines) {
            ProviderEnginePort previous = byCode.put(engine.code(), engine);
            if (previous != null) {
                throw new IllegalStateException(
                        "Deux moteurs déclarent le code « " + engine.code() + " » : "
                                + previous.getClass().getName() + " et "
                                + engine.getClass().getName()
                                + ". Le code est le lien stable avec les fournisseurs "
                                + "enregistrés, il doit être unique.");
            }
        }
        log.infof("Moteurs de notification disponibles : %s", byCode.keySet());
    }

    public Optional<ProviderEnginePort> find(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(byCode.get(code));
    }

    /** Tous les moteurs, pour l'écran d'administration. */
    public List<ProviderEnginePort> all() {
        return List.copyOf(byCode.values());
    }

    public List<ProviderEnginePort> forChannel(NotificationChannel channel) {
        return byCode.values().stream().filter(e -> e.channel() == channel).toList();
    }
}
