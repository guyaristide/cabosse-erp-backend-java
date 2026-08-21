package com.ntech.cabosse.notification.engine;

import com.ntech.cabosse.notification.entity.NotificationChannel;

import java.util.List;
import java.util.Map;

/**
 * Un moteur d'envoi : il se décrit lui-même, puis il envoie.
 *
 * <p>La description est ce qui rend la configuration extensible sans
 * toucher au back-office : l'écran d'administration lit
 * {@link #declaredParams()} et dessine le formulaire correspondant. Un
 * nouveau moteur se branche en ajoutant une classe qui implémente ce
 * port, rien d'autre.</p>
 *
 * <p>Le {@link #code()} est le lien stable avec les fournisseurs
 * enregistrés : il ne doit jamais changer une fois en production, même si
 * la classe est renommée ou déplacée.</p>
 */
public interface ProviderEnginePort {

    /** Identifiant stable, en majuscules ({@code SMTP}, {@code ORANGE_SMS}). */
    String code();

    /** Libellé affiché dans la liste des moteurs disponibles. */
    String label();

    NotificationChannel channel();

    /** Paramètres attendus, dans l'ordre d'affichage souhaité. */
    List<EngineParam> declaredParams();

    /**
     * Envoie le message. Ne lève pas d'exception pour un refus de
     * l'opérateur : rend un {@link SendOutcome} en échec porteur du motif.
     * Les exceptions restent réservées à l'imprévu, et sont rattrapées
     * par l'appelant.
     *
     * @param request message déjà rendu
     * @param params  valeurs du fournisseur, secrets déjà déchiffrés
     */
    SendOutcome send(SendRequest request, Map<String, String> params);

    /**
     * Vérifie que les paramètres requis sont renseignés. Suffisant pour
     * la plupart des moteurs ; à surcharger seulement si le moteur a une
     * contrainte propre. Ne jamais y deviner les règles du contrat
     * opérateur : un moteur qui exigeait un émetteur numérique a déjà
     * interdit des configurations pourtant valides.
     */
    default boolean isConfigured(Map<String, String> params) {
        if (params == null) return false;
        return declaredParams().stream()
                .filter(EngineParam::required)
                .allMatch(p -> {
                    String v = params.get(p.code());
                    return v != null && !v.isBlank();
                });
    }
}
