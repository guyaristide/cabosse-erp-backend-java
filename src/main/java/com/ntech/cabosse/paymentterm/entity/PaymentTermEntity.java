package com.ntech.cabosse.paymentterm.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.UUID;

/**
 * Condition de paiement du tenant (référentiel). Remplace la saisie
 * libre du champ {@code paymentTerms} des fournisseurs et des bons de
 * commande par une liste partagée et dédupliquée.
 *
 * <p>Tenant-scoped. Le {@code name} est la valeur stockée sur les
 * documents métier (ex. {@code supplier.paymentTerms = "30 jours fin de
 * mois"}). Pas de seed : la liste se construit à l'usage.</p>
 */
public class PaymentTermEntity {

    @BsonId
    public UUID id;

    /** Code stable (slug). */
    public String code;

    /** Libellé affiché et stocké (ex. {@code "30 jours fin de mois"}). */
    public String name;

    public boolean active = true;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;

    public PaymentTermEntity() {}
}
