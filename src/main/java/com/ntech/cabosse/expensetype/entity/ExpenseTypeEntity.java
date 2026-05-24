package com.ntech.cabosse.expensetype.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.UUID;

/**
 * Catégorie de dépense d'exploitation. Tenant-scoped — chaque tenant
 * définit ses propres catégories (transport, électricité, communication…)
 * mappées à un compte SYSCOHADA pour la consolidation comptable.
 */
public class ExpenseTypeEntity {

    @BsonId
    public UUID id;

    public String code;
    public String name;
    public String description;

    /**
     * Macro-catégorie pour les filtres / agrégats reporting.
     * Valeurs libres mais on suggère côté UI : LOGISTICS, UTILITIES,
     * ADMIN, MARKETING, PERSONNEL, FINANCIAL, OTHER.
     */
    public String category;

    /** Numéro de compte SYSCOHADA (ex. {@code 605}, {@code 622}). */
    public String syscohadaAccount;

    public boolean active = true;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
}
