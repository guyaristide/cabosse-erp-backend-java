package com.ntech.cabosse.notification.service;

import com.ntech.cabosse.notification.engine.ProviderEnginePort;
import com.ntech.cabosse.notification.engine.ProviderEngineRegistry;
import com.ntech.cabosse.notification.entity.NotificationChannel;
import com.ntech.cabosse.notification.entity.NotificationProviderEntity;
import com.ntech.cabosse.notification.entity.NotificationUsage;
import com.ntech.cabosse.notification.repository.NotificationProviderRepository;
import com.ntech.cabosse.settings.service.SecretCipher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Choisit les passerelles à essayer pour un couple (canal, usage), dans
 * l'ordre de préférence, et déchiffre leurs secrets.
 *
 * <p>C'est le <strong>seul</strong> point de déchiffrement : partout
 * ailleurs, un secret de passerelle reste chiffré. Concentrer le
 * déchiffrement rend l'audit possible et évite qu'un secret finisse dans
 * une réponse d'API par distraction.</p>
 *
 * <p>Un fournisseur peut être actif sans être utilisable : moteur absent
 * de la livraison, paramètre requis vide, secret indéchiffrable. Ces cas
 * sont écartés ici avec une trace, jamais silencieusement.</p>
 */
@ApplicationScoped
public class ProviderResolver {

    @Inject NotificationProviderRepository providers;
    @Inject ProviderEngineRegistry engines;
    @Inject SecretCipher cipher;
    @Inject Logger log;

    /**
     * Passerelles utilisables pour ce couple, la préférée en tête.
     * Liste vide si le canal n'est pas configuré : dans ce cas, il n'y a
     * rien à drainer et aucune tentative ne doit être consommée.
     */
    public List<ResolvedProvider> resolve(NotificationChannel channel, NotificationUsage usage) {
        List<NotificationProviderEntity> candidates = providers.listActive(channel).stream()
                .filter(p -> p.priorityFor(usage).isPresent())
                .sorted(Comparator.comparingInt(p -> p.priorityFor(usage).orElse(Integer.MAX_VALUE)))
                .toList();

        List<ResolvedProvider> usable = new ArrayList<>();
        for (NotificationProviderEntity provider : candidates) {
            resolveOne(provider).ifPresent(usable::add);
        }
        return usable;
    }

    /** Vérifie et prépare un fournisseur précis (essai depuis l'administration). */
    public Optional<ResolvedProvider> resolveOne(NotificationProviderEntity provider) {
        Optional<ProviderEnginePort> engine = engines.find(provider.engineCode);
        if (engine.isEmpty()) {
            log.warnf("Fournisseur « %s » actif mais moteur %s absent de la livraison : ignoré.",
                    provider.label, provider.engineCode);
            return Optional.empty();
        }
        Map<String, String> params;
        try {
            params = decrypt(provider);
        } catch (Exception e) {
            log.errorf(e, "Secrets illisibles pour le fournisseur « %s » : ignoré.", provider.label);
            return Optional.empty();
        }
        if (!engine.get().isConfigured(params)) {
            log.warnf("Fournisseur « %s » incomplet (paramètre requis vide) : ignoré.",
                    provider.label);
            return Optional.empty();
        }
        return Optional.of(new ResolvedProvider(provider, engine.get(), params));
    }

    /** Un canal a-t-il au moins un fournisseur actif ? Lecture bon marché. */
    public boolean channelHasActiveProvider(NotificationChannel channel) {
        return providers.hasActive(channel);
    }

    private Map<String, String> decrypt(NotificationProviderEntity provider) {
        Map<String, String> clear = new HashMap<>();
        Set<String> secrets = provider.secretKeys != null ? provider.secretKeys : Set.of();
        Map<String, String> stored = provider.params != null ? provider.params : Map.of();
        for (Map.Entry<String, String> e : stored.entrySet()) {
            String value = e.getValue();
            if (value != null && secrets.contains(e.getKey())) {
                value = cipher.decrypt(value);
            }
            clear.put(e.getKey(), value);
        }
        return clear;
    }
}
