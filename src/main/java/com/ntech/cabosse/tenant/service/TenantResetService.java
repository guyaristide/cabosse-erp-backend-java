package com.ntech.cabosse.tenant.service;

import com.mongodb.client.MongoClient;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.migration.TenantMigrationRunner;
import com.ntech.cabosse.site.entity.SiteEntity;
import com.ntech.cabosse.site.entity.SiteType;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import com.ntech.cabosse.user.repository.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Remise à plat des données d'une structure.
 *
 * <p>Sert à repartir d'une base vierge après une période d'essai ou une
 * reprise de données ratée, sans avoir à reprovisionner la structure ni à
 * recréer les comptes. Ce qui est détruit l'est <strong>définitivement</strong>
 * et sans sauvegarde : c'est la raison pour laquelle l'appel exige que
 * l'appelant recopie le nom de la structure.</p>
 *
 * <h3>Ce qui disparaît</h3>
 * <p>Toutes les données d'exploitation : membres, parcelles, récoltes,
 * achats, ventes, stocks, écritures, règlements, campagnes, ainsi que les
 * référentiels que la structure a saisis elle-même.</p>
 *
 * <h3>Ce qui survit</h3>
 * <ul>
 *   <li>Les <strong>comptes utilisateurs</strong>, administrateur compris.
 *       Ils vivent dans le plan de contrôle, que cette opération ne touche
 *       pas.</li>
 *   <li>Les <strong>profils de droits</strong>, sauvegardés puis restaurés
 *       à l'identique. Les recréer leur donnerait de nouveaux
 *       identifiants, et chaque collaborateur perdrait ses accès sans
 *       qu'on le lui dise.</li>
 *   <li>L'<strong>identité et l'abonnement</strong> de la structure : nom,
 *       modèle d'organisation, activités, plan. Ils vivent eux aussi dans
 *       le plan de contrôle, donc les capacités restent les mêmes.</li>
 *   <li>Un <strong>site par défaut</strong>, recréé comme au provisioning :
 *       sans site, la structure ne pourrait plus rien saisir.</li>
 * </ul>
 *
 * <p>Le reste est reconstruit par les migrations, exactement comme à
 * l'ouverture : collections, index, plan comptable, référentiels semés.</p>
 */
@ApplicationScoped
public class TenantResetService {

    private static final String ROLES_COLLECTION = "tenant_roles";

    @Inject MongoClient mongoClient;
    @Inject TenantRepository tenants;
    @Inject UserRepository users;
    @Inject TenantMigrationRunner migrationRunner;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;
    @Inject Logger log;

    /**
     * Remet la structure à son état d'ouverture.
     *
     * @param tenantId    structure visée
     * @param confirmation nom de la structure, recopié par l'appelant
     */
    public void resetToInitialState(UUID tenantId, String confirmation) {
        TenantEntity tenant = tenants.findById(tenantId);
        if (tenant == null) {
            throw new NotFoundException(Messages.msg("m.tnt-not-found", tenantId));
        }
        // Recopier le nom est le seul geste qui distingue une remise à
        // plat voulue d'un clic malheureux. La comparaison ignore la casse
        // et les espaces de bord, pas le contenu.
        if (confirmation == null
                || !confirmation.trim().toLowerCase(Locale.ROOT)
                        .equals(tenant.name == null ? "" : tenant.name.trim().toLowerCase(Locale.ROOT))) {
            throw new BusinessException(Messages.msg("m.tnt-reset-confirmation-mismatch"));
        }

        String databaseName = tenant.databaseName;
        var database = mongoClient.getDatabase(databaseName);

        // 1. Les profils de droits sont sauvegardés tels quels. Leurs
        //    identifiants sont référencés par les comptes utilisateurs du
        //    plan de contrôle : les régénérer priverait silencieusement
        //    chaque collaborateur de ses accès.
        List<Document> roles = database.getCollection(ROLES_COLLECTION)
                .find().into(new ArrayList<>());

        // 2. La base part en entier, journal des migrations compris : c'est
        //    ce qui garantit que la reconstruction est celle d'une base
        //    neuve, et non un nettoyage partiel qui laisserait des restes.
        database.drop();

        // 3. Reconstruction par les migrations, comme au provisioning.
        migrationRunner.runMigrationsFor(databaseName);

        // 4. Restauration des profils, à l'identique.
        //
        //    Les migrations viennent d'en semer un jeu neuf : il porte les
        //    mêmes codes mais de nouveaux identifiants, et entrerait en
        //    collision avec ceux qu'on restaure. Ce sont les profils
        //    sauvegardés qui font foi, puisque ce sont eux que les comptes
        //    utilisateurs référencent — le semis est écarté.
        if (!roles.isEmpty()) {
            var collection = mongoClient.getDatabase(databaseName).getCollection(ROLES_COLLECTION);
            collection.deleteMany(new Document());
            collection.insertMany(roles);
        }

        // 5. Un site, sans quoi plus aucune saisie n'est possible.
        seedDefaultSite(databaseName);

        long remainingUsers = users.find("tenantId", tenantId).count();
        log.warnf("Tenant %s reset to initial state by %s (%d profils restaurés, %d comptes conservés)",
                tenant.slug, actor(), roles.size(), remainingUsers);

        audit.event(AuditEventType.TENANT_DATA_RESET)
                .actorEmail(actor())
                .target("tenant", tenant.id.toString(), tenant.name)
                .tenant(tenant.id, tenant.name)
                .description("Données remises à plat : " + roles.size() + " profil(s) et "
                        + remainingUsers + " compte(s) conservés")
                .record();
    }

    private void seedDefaultSite(String databaseName) {
        SiteEntity site = new SiteEntity();
        site.id = com.github.f4b6a3.uuid.UuidCreator.getTimeOrderedEpoch();
        site.type = SiteType.TRANSFORMATION.name();
        site.name = "Siège";
        site.code = "siege";
        site.active = true;
        site.createdAt = Instant.now();
        site.updatedAt = site.createdAt;
        mongoClient.getDatabase(databaseName)
                .getCollection("sites", SiteEntity.class)
                .insertOne(site);
    }

    private String actor() {
        try { return jwt != null ? jwt.getName() : null; } catch (Exception e) { return null; }
    }
}
