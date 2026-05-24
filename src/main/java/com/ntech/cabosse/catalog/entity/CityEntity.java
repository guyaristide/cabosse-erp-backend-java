package com.ntech.cabosse.catalog.entity;

import com.ntech.cabosse.shared.persistence.ControlPlane;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;

import java.util.UUID;

/**
 * Ville référencée par le catalogue. UUID en clé parce que les noms ne
 * sont pas garantis uniques entre pays (et qu'on indexe par
 * {@code regionCode} / {@code countryCode}).
 *
 * <p>Au démarrage on seede les chef-lieux et grandes communes de Côte
 * d'Ivoire (~55 entrées). La collection est étendue à la demande.</p>
 */
@MongoEntity(database = ControlPlane.DATABASE, collection = ControlPlane.Collections.CITIES)
public class CityEntity extends PanacheMongoEntityBase {

    @BsonId
    public UUID id;

    public String name;

    /** FK {@link RegionEntity#code}. */
    public String regionCode;

    /** FK {@link CountryEntity#code}. Dénormalisé pour filtres rapides. */
    public String countryCode;

    public boolean isActive;

    public CityEntity() {}
}
