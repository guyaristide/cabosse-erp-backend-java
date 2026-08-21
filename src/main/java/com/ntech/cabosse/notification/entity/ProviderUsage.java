package com.ntech.cabosse.notification.entity;

/**
 * Rattachement d'un fournisseur à un usage, avec son rang de préférence
 * (0 = essayé en premier).
 *
 * <p>Les rangs sont réécrits en bloc à l'enregistrement, jamais permutés
 * deux à deux : une permutation laisse des doublons de rang dès que deux
 * administrateurs modifient l'ordre en même temps.</p>
 */
public class ProviderUsage {

    public NotificationUsage usage;
    public int priority;

    public ProviderUsage() {}

    public ProviderUsage(NotificationUsage usage, int priority) {
        this.usage = usage;
        this.priority = priority;
    }
}
