package com.ntech.cabosse.catalog.entity;

import com.ntech.cabosse.shared.persistence.ControlPlane;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;

/**
 * Référentiel pays. Vit dans le plan contrôle, partagé par tous les tenants.
 *
 * <p>La clé primaire est le code ISO 3166-1 alpha-2 ({@code "CI"}, {@code "FR"},
 * {@code "SN"}…). Lisible, stable, universel — pas d'UUID.</p>
 *
 * <p>Seedé par {@code CatalogSeeder} à partir de
 * {@code resources/catalog/countries.json}. {@code isActive=false} pour
 * désactiver côté UI sans supprimer (territoires sans usage, conflits).</p>
 */
@MongoEntity(database = ControlPlane.DATABASE, collection = ControlPlane.Collections.COUNTRIES)
public class CountryEntity extends PanacheMongoEntityBase {

    @BsonId
    public String code;

    public String nameFr;
    public String nameEn;

    /** Indicatif téléphonique international avec le {@code +} ({@code "+225"}). */
    public String dialCode;

    public boolean isActive;

    public CountryEntity() {}
}
