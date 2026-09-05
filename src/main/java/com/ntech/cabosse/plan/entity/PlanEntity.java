package com.ntech.cabosse.plan.entity;

import com.ntech.cabosse.shared.persistence.ControlPlane;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Plan tarifaire de la plateforme. Vit dans le plan contrôle
 * ({@code cabosse_control.plans}).
 *
 * <p>La clé primaire est le {@code code} ({@code "starter"}, {@code "pro"},
 * {@code "scale"}, {@code "enterprise"}) plutôt qu'un UUID, parce que :
 * <ul>
 *   <li>les codes sont stables (non renommables sans migration),</li>
 *   <li>ils sont référencés par {@code TenantEntity.planCode} et c'est
 *       plus lisible qu'un UUID dans les requêtes / logs.</li>
 * </ul>
 *
 * <p>Les montants sont en {@link BigDecimal} (jamais {@code double} sur
 * du monétaire, cf. règle projet). Devise XOF par défaut au démarrage.</p>
 */
@MongoEntity(database = ControlPlane.DATABASE, collection = ControlPlane.Collections.PLANS)
public class PlanEntity extends PanacheMongoEntityBase {

    /** Code stable du plan ({@code "starter"}, {@code "pro"}, etc.). */
    @BsonId
    public String code;

    public String name;
    public String description;

    public BigDecimal monthlyPrice;
    public BigDecimal yearlyPrice;

    public int maxUsers;
    public int maxSites;
    /** Plafond de producteurs membres. Nul ou absent : non contraint. */
    public int maxMembers;

    /** Codes des modules inclus ({@code "achats"}, {@code "production"}, …). */
    public List<String> includedModules = new ArrayList<>();

    /** Libellés des features additionnelles (export FEC, SLA, etc.). */
    public List<String> features = new ArrayList<>();

    /** Visible aux nouveaux tenants (false = plan historique conservé pour les anciens). */
    public boolean active;

    public PlanEntity() {}
}
