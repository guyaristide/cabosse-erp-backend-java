package com.ntech.cabosse.catalog.entity;

import com.ntech.cabosse.shared.persistence.ControlPlane;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;

/**
 * Subdivision administrative d'un pays. Niveau auquel l'utilisateur final
 * pioche dans un formulaire (en CI : 31 régions + 2 districts autonomes).
 *
 * <p>La hiérarchie complète (district → région → commune) est aplatie ici
 * au niveau "région" parce que c'est le grain pertinent pour l'utilisateur.
 * Le {@code districtCode} préserve la traçabilité administrative pour les
 * reports / exports SYSCOHADA.</p>
 *
 * <p>Clé primaire = code court interne ({@code "GBE"}, {@code "ABJ"}…),
 * stable, lisible dans les payloads et URLs.</p>
 */
@MongoEntity(database = ControlPlane.DATABASE, collection = ControlPlane.Collections.REGIONS)
public class RegionEntity extends PanacheMongoEntityBase {

    @BsonId
    public String code;

    public String name;

    /** FK {@link CountryEntity#code} (ISO-2). */
    public String countryCode;

    /**
     * Code du district administratif parent ({@code "D-AB"}, {@code "D-BS"}…).
     * {@code null} si la région est elle-même un district autonome (Abidjan,
     * Yamoussoukro).
     */
    public String districtCode;

    public boolean isActive;

    public RegionEntity() {}
}
