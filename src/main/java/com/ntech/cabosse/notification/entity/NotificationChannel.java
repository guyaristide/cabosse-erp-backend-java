package com.ntech.cabosse.notification.entity;

/**
 * Canal d'acheminement d'une notification. Chaque canal a sa propre file
 * de drainage : une passerelle SMTP muette ne doit pas retenir les SMS.
 */
public enum NotificationChannel {
    EMAIL,
    SMS,
    /** Réservé au moteur FCM (phase 2 de l'épic). */
    PUSH
}
