package com.ntech.cabosse.agriculture.parcel.entity;

import org.bson.Document;
import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Parcelle agricole géolocalisée. Tenant-scopée (collection {@code parcels}).
 *
 * <p>Disponible si la capacité
 * {@link com.ntech.cabosse.tenant.capability.TenantCapability#HAS_PARCELS}
 * est active. Indépendant de la filière : utilisable par production
 * cacao, hévéa, palmier, coton, fruits, etc.</p>
 *
 * <p><strong>GeoJSON</strong> : {@link #gpsPolygon} est un document
 * BSON conforme à GeoJSON Polygon (RFC 7946) pour permettre l'index
 * Mongo {@code 2dsphere} sur ce champ. Coordonnées en
 * <strong>[longitude, latitude]</strong> (convention GeoJSON, attention
 * c'est l'inverse de Leaflet/OSM par défaut).</p>
 *
 * <pre>{@code
 * gpsPolygon = {
 *   "type": "Polygon",
 *   "coordinates": [
 *     [ [lng,lat], [lng,lat], [lng,lat], [lng,lat] ]  // anneau fermé : premier == dernier
 *   ]
 * }
 * }</pre>
 *
 * <p>Pour les parcelles &lt; 4 ha où la précision GPS d'un point central
 * suffit (cf. EUDR Art. 3a), {@link #gpsPolygon} peut être null et
 * {@link #gpsCenter} seul renseigné.</p>
 */
public class ParcelEntity {

    @BsonId
    public UUID id;

    /** Référence affichable {@code PR-YYYY-NNNN}. Unique par tenant. */
    public String code;

    /** Nom usuel ("Parcelle 1 famille Diabaté", "Sud route Méagui"…). */
    public String name;

    /** Surface en hectares. Saisie ou dérivée du polygone. */
    public BigDecimal surfaceHa;

    /**
     * Polygone GeoJSON conforme {@code 2dsphere}. {@code null} si seul
     * un point GPS central est connu (parcelle &lt; 4 ha).
     */
    public Document gpsPolygon;

    /**
     * Centroid GPS [longitude, latitude] (convention GeoJSON). Toujours
     * renseigné, soit calculé depuis le polygone soit saisi seul pour
     * les petites parcelles.
     */
    public List<Double> gpsCenter;

    /** Variété cultivée (ex. "Mercedes", "F1", "Hévéa GT1"). Texte libre. */
    public String variety;

    /** Année de plantation (sert au calcul de l'âge des plants). */
    public Integer plantingYear;

    /**
     * Région administrative de la parcelle (backlog PARC-01). Code du
     * référentiel régions <strong>tenant</strong> ({@code regions}). Propre à
     * la parcelle, saisi indépendamment du domicile du producteur.
     */
    public String regionCode;

    /** Département de la parcelle (code du référentiel départements tenant), PARC-01. */
    public String departmentCode;

    /**
     * Rendement et estimation de production par campagne (backlog PARC-01).
     * Une entrée par campagne renseignée, saisie manuelle.
     */
    public List<ParcelCampaignYield> campaignYields = new ArrayList<>();

    /** Certifications portées par la parcelle (Rainforest, Bio, UTZ, Fairtrade…). */
    public List<String> certifications = new ArrayList<>();

    /**
     * UUID du {@link com.ntech.cabosse.members.entity.MemberEntity}
     * propriétaire / exploitant. Optionnel : une parcelle peut être
     * directement détenue par le tenant (cas exploitation privée).
     */
    public UUID memberId;

    /**
     * Snapshot du nom du membre au moment de l'affectation. Sert aux
     * listes / exports sans join. Synchronisé par {@code ParcelService}
     * quand on change de membre ou que le nom du membre change.
     */
    public String memberName;

    public ParcelStatus status = ParcelStatus.ACTIVE;

    public String notes;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
    public String createdByEmail;

    public ParcelEntity() {}
}
