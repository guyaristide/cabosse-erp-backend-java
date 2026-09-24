package com.ntech.cabosse.user.entity;

import java.time.Instant;

/**
 * Un droit accordé ou retiré à une personne, hors de ses profils
 * (backlog ADM-03).
 *
 * <p>Elle porte qui l'a posée, quand, et pourquoi. Sans le motif, une
 * exception devient au bout de quelques mois un droit dont plus personne
 * ne sait s'il tient toujours : celui qui relit la fiche doit pouvoir
 * décider de la garder ou de l'enlever sans aller demander.</p>
 *
 * <p>Elle ne porte pas de date de fin : l'arbitrage du 24/09/2026 a
 * retenu des exceptions permanentes, retirées à la main.</p>
 */
public class UserPermissionException {

    /** Code de la permission, tel que {@code Permission.code()}. */
    public String code;

    public PermissionExceptionMode mode;

    /** Pourquoi cette personne, et pas son profil. */
    public String reason;

    public String grantedByEmail;

    public Instant grantedAt;

    public UserPermissionException() {}
}
