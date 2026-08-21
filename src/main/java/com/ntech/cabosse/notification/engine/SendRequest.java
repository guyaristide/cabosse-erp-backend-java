package com.ntech.cabosse.notification.engine;

import com.ntech.cabosse.notification.entity.NotificationChannel;

/**
 * Ce qu'un moteur reçoit à l'envoi : le message déjà rendu. Le rendu a
 * lieu à l'enfilage, pas ici — un moteur n'a pas à connaître de gabarit.
 *
 * @param channel canal, pour les moteurs qui en servent plusieurs
 * @param target  adresse, numéro ou jeton d'appareil
 * @param subject sujet (courriel) ou null
 * @param body    corps rendu
 */
public record SendRequest(NotificationChannel channel, String target,
                          String subject, String body) {}
