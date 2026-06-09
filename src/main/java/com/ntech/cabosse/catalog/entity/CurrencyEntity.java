package com.ntech.cabosse.catalog.entity;

import com.ntech.cabosse.shared.persistence.ControlPlane;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;

/**
 * Catalogue des devises supportées par la plateforme (codes ISO 4217).
 * Une seule devise active à la fois par tenant (cf. {@code TenantPreferences.currency}).
 * Décision NEIBA-TECH-2026-003 §3 : pas de multi-devises actives ; ce
 * catalogue sert uniquement à formater et à valider les choix au
 * provisioning.
 *
 * <p>Clé primaire = code ISO 4217 ({@code "XOF"}, {@code "EUR"}…).
 * Édition réservée au back-office plateforme.</p>
 */
@MongoEntity(database = ControlPlane.DATABASE, collection = ControlPlane.Collections.CURRENCIES)
public class CurrencyEntity extends PanacheMongoEntityBase {

    @BsonId
    public String code;

    public String label;

    /** Symbole monétaire affiché ({@code FCFA}, {@code €}, {@code $}, {@code ₵}…). */
    public String symbol;

    /** Nombre de décimales standard pour cette devise (0 pour XOF/XAF, 2 sinon). */
    public int decimalPlaces;

    /**
     * Position du symbole par rapport au montant : {@code BEFORE} ($ 1 234)
     * ou {@code AFTER} (1 234 FCFA). Convention par devise, alignée sur
     * l'usage local courant.
     */
    public String position;

    public boolean isActive;

    public CurrencyEntity() {}
}
