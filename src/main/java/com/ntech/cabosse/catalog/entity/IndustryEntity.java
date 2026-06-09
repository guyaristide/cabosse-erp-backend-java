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

    /**
     * Capacités fonctionnelles activées par cette filière. Chaque entrée
     * est le nom (string) d'une valeur de
     * {@link com.ntech.cabosse.tenant.capability.TenantCapability}.
     *
     * <p>Le service {@code TenantCapabilityService.capabilitiesOf()}
     * agrège ces capacités sur l'ensemble des activités déclarées par
     * le tenant, puis y ajoute celles dérivées de
     * {@code TenantEntity.organizationModel} (notamment HAS_MEMBERS et
     * HAS_SUSTAINABILITY pour COOPERATIVE).</p>
     *
     * <p>Vide ou {@code null} si la filière n'active aucune capacité
     * spécifique (le tenant utilise seulement le cœur agnostique).</p>
     */
    public java.util.List<String> activates = new java.util.ArrayList<>();

    public IndustryEntity() {}
}
