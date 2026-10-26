package com.ntech.cabosse.supplier.entity;

import java.util.Locale;

/**
 * Ce qu'un tiers sorti du sociétariat continue d'être pour la coopérative
 * (demandé le 26/10/2026) : un planteur qui livre sans être membre, ou un
 * délégué collecteur non membre. La qualité se choisit à la conversion et
 * se pose sur le fournisseur, pour que les listes le disent.
 */
public enum NonMemberRole {
    PRODUCER, DELEGATE;

    public static NonMemberRole parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
