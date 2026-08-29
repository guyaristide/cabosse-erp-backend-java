package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.shared.migration.CapabilityMigrationGuard;
import com.ntech.cabosse.shared.persistence.ControlPlane;
import com.ntech.cabosse.tenant.capability.TenantCapability;
import com.ntech.cabosse.tenant.entity.TenantOrganizationModel;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Migration 078 — une structure ouvre avec ses postes déjà en place.
 *
 * <p>Jusqu'ici un tenant naissait avec un seul profil, {@code Opérateur},
 * qui reprenait les accès d'avant les droits et accordait large. Composer
 * les autres à la main suppose de connaître le catalogue entier : on
 * ouvrait donc en confiant tout à un profil unique, faute de mieux, ce qui
 * défait le découpage au moment même où il devrait servir.</p>
 *
 * <p>Trois profils sont semés, chacun correspondant à un poste réel :</p>
 *
 * <ul>
 *   <li><strong>Administrateur</strong> — tous les droits que les capacités
 *       du tenant rendent applicables. Il permet de déléguer
 *       l'administration à une seconde personne sans lui confier le compte
 *       propriétaire.</li>
 *   <li><strong>Gouvernance</strong> — le conseil approuve et lit, il ne
 *       saisit pas et ne décaisse pas. Il porte le barème de campagne,
 *       parce que le prix payé au producteur est une décision d'organe, pas
 *       un libellé de référentiel.</li>
 *   <li><strong>Comptable</strong> — les écritures, la trésorerie, la
 *       clôture et la sortie des fonds, sur une lecture de tout ce qui les
 *       alimente.</li>
 * </ul>
 *
 * <p>Ensemble, ils <strong>referment le circuit d'un financement</strong>
 * sans qu'aucun ne le parcoure seul : l'opérateur dépose la demande, le
 * conseil l'approuve, le comptable décaisse. Aucun profil ne détient deux
 * de ces trois gestes, ce qui est exactement ce qui rend le découpage
 * utile.</p>
 *
 * <p>Ce sont des points de départ, pas des cadres : chacun se modifie et se
 * désactive. La migration ne crée que les profils absents, si bien qu'un
 * profil retouché ou supprimé n'est jamais rétabli par un redémarrage.</p>
 */
@ChangeUnit(id = "seed_tenant_profiles", order = "078", author = "neiba")
public class M078_SeedTenantProfiles {

    /** Tous les droits, filtrés par les capacités du tenant. */
    private static final List<Permission> ADMINISTRATOR = List.of(Permission.values());

    /**
     * Le conseil approuve et lit.
     *
     * <p>Ni écriture opérationnelle, ni décaissement : un organe qui
     * approuve puis remet lui-même les fonds n'approuve plus rien.</p>
     */
    private static final List<Permission> GOVERNANCE = List.of(
            Permission.REFERENTIAL_READ, Permission.CAMPAIGN_PRICE_WRITE,
            Permission.PURCHASE_READ, Permission.PURCHASE_APPROVE,
            Permission.COLLECTION_READ, Permission.COLLECTION_ADVANCE_APPROVE,
            Permission.MEMBER_READ,
            Permission.MEMBER_CREDIT_APPROVE, Permission.MEMBER_CREDIT_APPROVE_GOVERNANCE,
            Permission.PARCEL_READ, Permission.PROCESSING_READ,
            Permission.STOCK_READ, Permission.SALE_READ,
            Permission.ACCOUNTING_READ,
            Permission.EUDR_READ, Permission.TRACEABILITY_READ,
            Permission.REPORTING_READ, Permission.EXECUTIVE_READ,
            Permission.SETTINGS_READ);

    /**
     * Les écritures, la trésorerie et la sortie des fonds, sur une lecture
     * de ce qui les alimente.
     *
     * <p>Le décaissement lui revient parce qu'il tient la caisse : la
     * troisième main du circuit, distincte de celle qui dépose la demande
     * et de celle qui l'approuve. Il ne peut ni l'une ni l'autre, ce qui
     * est exactement ce qui rend le découpage utile.</p>
     */
    private static final List<Permission> ACCOUNTANT = List.of(
            Permission.REFERENTIAL_READ,
            Permission.PURCHASE_READ,
            Permission.COLLECTION_READ, Permission.COLLECTION_ADVANCE_DISBURSE,
            Permission.MEMBER_READ, Permission.MEMBER_CREDIT_DISBURSE,
            Permission.STOCK_READ,
            Permission.SALE_READ,
            Permission.ACCOUNTING_READ, Permission.ACCOUNTING_WRITE, Permission.ACCOUNTING_CLOSE,
            Permission.TREASURY_WRITE,
            Permission.REPORTING_READ);

    @Execution
    public void execute(MongoDatabase database, MongoClient client) {
        Set<TenantCapability> caps =
                CapabilityMigrationGuard.capabilitiesFor(database.getName(), client);

        seed(database, "ADMINISTRATEUR", "Administrateur",
                "Tous les droits ouverts à la structure. À réserver aux personnes "
                        + "qui administrent le paramétrage et les comptes.",
                ADMINISTRATOR, caps);

        seed(database, "GOUVERNANCE", governanceName(database, client),
                "Approuve les financements et les demandes d'achat, fixe le barème "
                        + "de campagne, et lit l'ensemble de l'activité. N'engage aucune "
                        + "écriture d'exploitation et ne décaisse pas.",
                GOVERNANCE, caps);

        seed(database, "COMPTABLE", "Comptable",
                "Tient les écritures, la trésorerie et la clôture, et décaisse les "
                        + "financements approuvés. Lit les achats, les ventes, les stocks "
                        + "et la collecte sans y saisir.",
                ACCOUNTANT, caps);
    }

    /**
     * Le nom du profil de gouvernance suit la structure.
     *
     * <p>Une entreprise privée n'a pas de conseil d'administration au sens
     * coopératif : lui proposer un « président du conseil » nommerait un
     * organe qui n'existe pas chez elle. Le périmètre de droits, lui, ne
     * change pas : approuver et lire.</p>
     */
    private String governanceName(MongoDatabase database, MongoClient client) {
        Document tenant = client.getDatabase(ControlPlane.DATABASE)
                .getCollection(ControlPlane.Collections.TENANTS)
                .find(Filters.eq("databaseName", database.getName())).first();
        String raw = tenant != null ? tenant.getString("organizationModel") : null;
        TenantOrganizationModel model;
        try {
            model = raw != null ? TenantOrganizationModel.valueOf(raw) : null;
        } catch (IllegalArgumentException e) {
            model = null;
        }
        if (model == TenantOrganizationModel.COOPERATIVE
                || model == TenantOrganizationModel.INFORMAL_GROUP) {
            return "Président du conseil d'administration";
        }
        return "Direction générale";
    }

    /**
     * Crée le profil s'il n'existe pas déjà sous ce code.
     *
     * <p>Un profil que l'administrateur a retouché, vidé ou désactivé ne
     * doit pas se voir rétabli au redémarrage suivant : la migration ne
     * comble que l'absence.</p>
     */
    private void seed(MongoDatabase database, String code, String name, String description,
                      List<Permission> permissions, Set<TenantCapability> caps) {
        var roles = database.getCollection("tenant_roles");
        if (roles.find(Filters.eq("code", code)).first() != null) return;

        // Une case sans objet n'est pas un droit : le profil ne porte que
        // ce que les capacités du tenant rendent applicable.
        List<String> codes = permissions.stream()
                .filter(p -> p.availableFor(caps))
                .map(Enum::name)
                .toList();
        if (codes.isEmpty()) return;

        Instant now = Instant.now();
        roles.insertOne(new Document("_id", UUID.randomUUID())
                .append("code", code)
                .append("name", name)
                .append("description", description)
                .append("permissions", new ArrayList<>(codes))
                .append("active", true)
                .append("createdAt", now)
                .append("updatedAt", now));
    }

    /**
     * Pas de retour en arrière.
     *
     * <p>Supprimer ces profils retirerait leurs accès aux comptes qui les
     * portent, y compris ceux qu'un administrateur a rattachés depuis.</p>
     */
    @RollbackExecution
    public void rollback(MongoDatabase database) {
        // Volontairement vide.
    }
}
