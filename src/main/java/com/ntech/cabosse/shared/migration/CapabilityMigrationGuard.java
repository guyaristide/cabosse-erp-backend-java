package com.ntech.cabosse.shared.migration;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.ntech.cabosse.shared.persistence.ControlPlane;
import com.ntech.cabosse.tenant.capability.TenantCapability;
import com.ntech.cabosse.tenant.entity.TenantOrganizationModel;
import org.bson.Document;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Garde réutilisable pour les migrations Mongock M014+ qui touchent des
 * collections optionnelles (membres, parcelles, EUDR, durabilité…).
 *
 * <p>Permet à un {@code @ChangeUnit} de vérifier qu'une capacité
 * fonctionnelle est active pour le tenant cible avant d'agir. Sinon
 * la migration s'arrête en no-op silencieux — Mongock la marquera comme
 * appliquée pour ne pas la re-tenter à chaque démarrage.</p>
 *
 * <p>Usage typique :</p>
 * <pre>{@code
 * @ChangeUnit(id = "create_members_collection", order = "014", author = "neiba")
 * public class M014_CreateMembersCollection {
 *     @Execution
 *     public void execute(MongoDatabase database, MongoClient client) {
 *         if (!CapabilityMigrationGuard.shouldRunFor(database, client, TenantCapability.HAS_MEMBERS)) {
 *             return; // tenant non concerné, no-op
 *         }
 *         // création de la collection + indexes
 *     }
 * }
 * }</pre>
 *
 * <p>La dérivation des capacités <strong>duplique volontairement</strong>
 * la logique de {@code TenantCapabilityService} parce qu'on est dans un
 * contexte hors CDI (changeUnit Mongock). Garder les deux services en
 * cohérence est une discipline du dev — un test d'intégration valide
 * que la même base tenant produit le même {@code Set} de capacités via
 * les deux chemins.</p>
 */
public final class CapabilityMigrationGuard {

    private CapabilityMigrationGuard() {}

    /**
     * Retourne {@code true} si le tenant correspondant à la base
     * {@code database} a la capacité {@code cap} active.
     *
     * @param database base tenant (telle que reçue par le changeUnit)
     * @param client   MongoClient injecté par {@code TenantMigrationRunner.addDependency}
     * @param cap      capacité à vérifier
     */
    public static boolean shouldRunFor(MongoDatabase database, MongoClient client,
                                       TenantCapability cap) {
        if (database == null || client == null || cap == null) return false;
        return capabilitiesFor(database.getName(), client).contains(cap);
    }

    /** Variante : lecture du set complet de capacités. */
    public static Set<TenantCapability> capabilitiesFor(String databaseName, MongoClient client) {
        Set<TenantCapability> caps = new HashSet<>();
        MongoDatabase control = client.getDatabase(ControlPlane.DATABASE);

        // ─── Lecture du tenant ───
        Document tenant = control.getCollection(ControlPlane.Collections.TENANTS)
                .find(Filters.eq("databaseName", databaseName))
                .projection(Projections.fields(
                        Projections.include("organizationModel", "activities", "certifications")
                ))
                .first();
        if (tenant == null) return caps;

        // ─── Dimension 1 : organizationModel ───
        String orgRaw = tenant.getString("organizationModel");
        TenantOrganizationModel org;
        try {
            org = orgRaw != null ? TenantOrganizationModel.valueOf(orgRaw) : TenantOrganizationModel.PRIVATE_COMPANY;
        } catch (IllegalArgumentException e) {
            org = TenantOrganizationModel.PRIVATE_COMPANY;
        }
        if (org == TenantOrganizationModel.COOPERATIVE || org == TenantOrganizationModel.INFORMAL_GROUP) {
            caps.add(TenantCapability.HAS_MEMBERS);
            caps.add(TenantCapability.HAS_SUSTAINABILITY);
        }
        @SuppressWarnings("unchecked")
        List<String> certifications = tenant.get("certifications", List.class);
        if (certifications != null && !certifications.isEmpty()) {
            caps.add(TenantCapability.HAS_SUSTAINABILITY);
        }

        // ─── Dimension 2 : activities → IndustryEntity.activates ───
        @SuppressWarnings("unchecked")
        List<Document> activities = tenant.get("activities", List.class);
        if (activities == null || activities.isEmpty()) return caps;

        var industries = control.getCollection(ControlPlane.Collections.INDUSTRIES);
        for (Document activity : activities) {
            String code = activity.getString("code");
            if (code == null) continue;
            Document ind = industries.find(Filters.eq("_id", code))
                    .projection(Projections.include("activates"))
                    .first();
            if (ind == null) continue;
            @SuppressWarnings("unchecked")
            List<String> activates = ind.get("activates", List.class);
            if (activates == null) continue;
            for (String capName : activates) {
                try {
                    caps.add(TenantCapability.valueOf(capName));
                } catch (IllegalArgumentException ignored) {
                    // code invalide dans le seed → silencieux
                }
            }
        }
        return caps;
    }
}
