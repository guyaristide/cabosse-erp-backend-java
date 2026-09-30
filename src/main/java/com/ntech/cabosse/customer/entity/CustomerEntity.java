package com.ntech.cabosse.customer.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Client (B2B ou B2C). Tenant-scoped. */
public class CustomerEntity {

    @BsonId
    public UUID id;

    public String code;
    public String name;

    /** {@link CustomerType} sérialisé en String. */
    public String type;

    /**
     * {@link CustomerChannelType} sérialisé en String (nullable). Aucune
     * migration Mongock requise : les clients existants restent à
     * {@code null} jusqu'à édition, et l'export les traite comme
     * « canal vide » sans erreur.
     */
    public String channelType;

    public String legalName;
    public String taxNumber;

    public String email;
    public String phone;
    public String addressLine;
    public String cityName;
    public String countryCode;

    public String contactName;

    /** Plafond de crédit (FCFA). {@code null} = pas de limite. */
    public BigDecimal creditLimit;

    public String notes;

    /**
     * Commission de collecte convenue avec ce client, campagne par
     * campagne, en montant par kilo facturé.
     *
     * <p>Quand la structure collecte pour compte, elle ne vend pas : elle
     * facture sa commission et reverse le reste. C'est ce taux qui sépare
     * les deux au décompte, et il se négocie d'une saison à l'autre comme
     * celui des délégués (arbitré le 29/09/2026).</p>
     *
     * <p>Une campagne absente de la liste n'a pas de commission convenue :
     * rien n'est facturé pour elle. Le même type que la rémunération des
     * délégués, qui dit déjà « un taux, pour une campagne ».</p>
     */
    public java.util.List<com.ntech.cabosse.supplier.entity.SupplierEntity.CampaignMargin>
            commissionByCampaign;

    public boolean active = true;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
}
