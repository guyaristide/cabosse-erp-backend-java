package com.ntech.cabosse.notification.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Un fournisseur configuré : un moteur, des valeurs, des usages. Vit dans
 * le plan de contrôle (la configuration des passerelles est une affaire
 * de plateforme, pas de tenant).
 *
 * <p>Le lien vers le moteur passe par un <strong>code stable</strong>, pas
 * par un nom de classe : renommer une classe ne doit pas débrancher une
 * passerelle en production.</p>
 */
public class NotificationProviderEntity {

    @BsonId
    public UUID id;

    /** Code du moteur ({@code SMTP}, {@code BREVO_API}, {@code ORANGE_SMS}). */
    public String engineCode;

    /** Nom donné par l'administrateur, pour distinguer deux comptes. */
    public String label;

    public NotificationChannel channel;

    /**
     * Actif au sens de l'administrateur. Distinct de « utilisable » :
     * un fournisseur actif dont le moteur est absent de la livraison, ou
     * dont un paramètre requis manque, ne peut rien émettre. Sans ce
     * contrôle, une passerelle paraît active et n'émet rien.
     */
    public boolean active;

    /** Valeurs des paramètres du moteur ; les secrets sont chiffrés. */
    public Map<String, String> params = new HashMap<>();

    /** Noms des paramètres stockés chiffrés. */
    public Set<String> secretKeys = new HashSet<>();

    /**
     * Usages servis et rang de préférence. La priorité appartient au
     * couple (canal, usage) : la même passerelle peut être première en
     * transactionnel et absente en alertes.
     */
    public List<ProviderUsage> usages = new ArrayList<>();

    public Instant createdAt;
    public Instant updatedAt;
    public String updatedBy;

    public NotificationProviderEntity() {}

    /** Rang de ce fournisseur pour un usage, ou vide s'il ne le sert pas. */
    public java.util.OptionalInt priorityFor(NotificationUsage usage) {
        if (usages == null) return java.util.OptionalInt.empty();
        for (ProviderUsage u : usages) {
            if (u != null && u.usage == usage) return java.util.OptionalInt.of(u.priority);
        }
        return java.util.OptionalInt.empty();
    }
}
