package com.ntech.cabosse.notification.service;

import com.ntech.cabosse.notification.engine.ProviderEnginePort;
import com.ntech.cabosse.notification.entity.NotificationProviderEntity;

import java.util.Map;

/**
 * Une passerelle prête à émettre : le fournisseur enregistré, son moteur
 * présent dans la livraison, et ses paramètres <strong>déchiffrés</strong>.
 *
 * <p>Les secrets n'existent en clair que dans cet objet, produit au seul
 * endroit qui déchiffre. Ne jamais le journaliser ni le renvoyer par
 * l'API.</p>
 */
public record ResolvedProvider(NotificationProviderEntity provider,
                               ProviderEnginePort engine,
                               Map<String, String> params) {

    public String engineCode() { return engine.code(); }

    public String label() {
        return provider.label != null && !provider.label.isBlank()
                ? provider.label : engine.label();
    }
}
