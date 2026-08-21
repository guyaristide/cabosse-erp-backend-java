package com.ntech.cabosse.notification.engine;

/**
 * Résultat d'un envoi. Un moteur ne lève pas d'exception pour un refus de
 * l'opérateur : il rend un échec porteur du motif, qui remonte tel quel
 * jusqu'à l'écran d'administration. Reformuler « numéro non autorisé sur
 * ce compte » en « échec d'envoi » fait perdre la seule information qui
 * permet de corriger la configuration.
 *
 * @param success           l'opérateur a accepté le message
 * @param providerMessageId identifiant rendu par l'opérateur, ou null
 * @param failureReason     motif d'échec tel que rendu, ou null
 */
public record SendOutcome(boolean success, String providerMessageId, String failureReason) {

    public static SendOutcome sent(String providerMessageId) {
        return new SendOutcome(true, providerMessageId, null);
    }

    public static SendOutcome failed(String reason) {
        return new SendOutcome(false, null, reason);
    }
}
