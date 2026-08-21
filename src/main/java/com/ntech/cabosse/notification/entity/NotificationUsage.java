package com.ntech.cabosse.notification.entity;

/**
 * Nature de l'envoi. Sépare ce qui doit passer coûte que coûte de ce qui
 * peut attendre : un code à usage unique et un rappel de stock bas ne
 * méritent pas la même passerelle ni le même ordre de préférence.
 *
 * <p>La priorité d'un fournisseur est portée par le couple
 * (canal, usage) : la passerelle la plus fiable et la plus chère peut
 * être première en transactionnel et absente en alertes.</p>
 */
public enum NotificationUsage {
    /** Codes à usage unique, invitations, réinitialisation de mot de passe. */
    TRANSACTIONAL,
    /** Rappels, seuils, récapitulatifs. */
    ALERT
}
