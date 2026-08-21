package com.ntech.cabosse.notification.entity;

/**
 * Cycle de vie d'une ligne de la file d'envoi.
 *
 * <pre>
 *   PENDING ──(prise atomique)──▶ SENDING ──(succès)──▶ SENT
 *      ▲                             │
 *      └──(échec, tentatives restantes, réarmé plus tard)
 *                                    │
 *                                    └──(plus de tentative)──▶ FAILED
 *   PENDING ──(date limite dépassée)──▶ EXPIRED
 * </pre>
 *
 * <p>{@code SENDING} n'est pas décoratif : c'est la marque de prise qui
 * empêche deux instances de drainer la même ligne. Une ligne restée en
 * {@code SENDING} au-delà du délai de reprise est reprise (le processus
 * qui l'avait prise n'a pas survécu à son envoi).</p>
 */
public enum DeliveryStatus {
    PENDING,
    SENDING,
    SENT,
    FAILED,
    EXPIRED
}
