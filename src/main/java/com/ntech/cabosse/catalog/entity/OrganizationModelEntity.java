package com.ntech.cabosse.catalog.entity;

import com.ntech.cabosse.shared.persistence.ControlPlane;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;

/**
 * Catalogue des modèles d'organisation qu'un tenant peut adopter
 * (coopérative, entreprise privée, groupement informel, entité publique).
 *
 * <p>Contrairement aux filières, l'ensemble des codes est <em>fermé</em> :
 * la clé primaire correspond exactement au nom d'une valeur de
 * {@link com.ntech.cabosse.tenant.entity.TenantOrganizationModel}, que le
 * tenant stocke en enum. Le back-office ne crée ni ne supprime d'entrée —
 * il édite seulement le libellé, la description et les capacités activées.</p>
 *
 * <p>Le champ {@code activates} pilote la dimension « structure » du calcul
 * de capacités dans {@code TenantCapabilityService} (ex : COOPERATIVE active
 * HAS_MEMBERS et HAS_SUSTAINABILITY). Le rendre éditable évite de coder ces
 * règles en dur.</p>
 */
@MongoEntity(database = ControlPlane.DATABASE, collection = ControlPlane.Collections.ORGANIZATION_MODELS)
public class OrganizationModelEntity extends PanacheMongoEntityBase {

    /** Nom d'une valeur de {@code TenantOrganizationModel} (ex : {@code "COOPERATIVE"}). */
    @BsonId
    public String code;

    public String label;

    public String description;

    public boolean isActive;

    /**
     * Capacités fonctionnelles activées par ce modèle d'organisation. Chaque
     * entrée est le nom (string) d'une valeur de
     * {@link com.ntech.cabosse.tenant.capability.TenantCapability}. Vide si le
     * modèle n'active aucune capacité spécifique.
     */
    public java.util.List<String> activates = new java.util.ArrayList<>();

    public OrganizationModelEntity() {}
}
