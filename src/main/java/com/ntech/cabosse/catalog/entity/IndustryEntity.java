package com.ntech.cabosse.catalog.entity;

import com.ntech.cabosse.shared.persistence.ControlPlane;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;

/**
 * Catalogue des activités / filières que peut déclarer un tenant.
 *
 * <p>Catalogue strict — les codes utilisés par {@code TenantActivity.code}
 * doivent obligatoirement référencer une entrée active ici. Le catalogue
 * sera éditable depuis le back-office plateforme (Phase C+).</p>
 *
 * <p>Clé primaire = code slug ({@code "chocolaterie"}, {@code "hevea-caoutchouc"}).
 * Lisible, stable, identique au {@code code} embarqué dans le tenant.</p>
 */
@MongoEntity(database = ControlPlane.DATABASE, collection = ControlPlane.Collections.INDUSTRIES)
public class IndustryEntity extends PanacheMongoEntityBase {

    @BsonId
    public String code;

    public String label;

    public String description;

    public boolean isActive;

    public IndustryEntity() {}
}
