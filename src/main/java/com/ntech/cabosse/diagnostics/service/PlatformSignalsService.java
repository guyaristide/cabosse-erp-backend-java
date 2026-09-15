package com.ntech.cabosse.diagnostics.service;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.diagnostics.dto.PlatformSignalDto;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.shared.tenant.TenantStatus;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ce qui va mal chez les structures, sans avoir à le demander structure
 * par structure.
 *
 * <p>Les contrôles de cohérence répondent bien, mais seulement à qui les
 * interroge, un tenant à la fois, après qu'un utilisateur s'est plaint.
 * L'administration de la plateforme n'avait aucun moyen de voir venir :
 * un carnet importé sans délégué reste invisible jusqu'au jour où le
 * comptable bute dessus, et personne côté éditeur ne le sait
 * (15/09/2026).</p>
 *
 * <p>Quelques compteurs par structure, choisis parce qu'ils annoncent un
 * blocage plutôt qu'ils ne le constatent. Le parcours est borné aux
 * structures actives et chaque base n'est ouverte que le temps de
 * compter : une défaillance sur l'une n'empêche pas de lire les
 * autres.</p>
 */
@ApplicationScoped
public class PlatformSignalsService {

    private static final Logger LOG = Logger.getLogger(PlatformSignalsService.class);

    /** Au-delà, la page n'est plus lisible et la requête plus tenable. */
    private static final int MAX_TENANTS = 200;

    /** Un bordereau qui attend depuis plus longtemps n'attend plus, il est bloqué. */
    private static final int STALE_AFTER_DAYS = 7;

    @Inject MongoClient mongoClient;
    @Inject TenantRepository tenants;
    @Inject AuditService audit;

    public List<PlatformSignalDto> signals(String actorEmail) {
        List<PlatformSignalDto> out = new ArrayList<>();
        for (TenantEntity tenant : tenants.findByStatus(TenantStatus.ACTIVE, 0, MAX_TENANTS)) {
            try {
                out.add(signalsOf(tenant));
            } catch (RuntimeException e) {
                // Une base injoignable ne doit pas emporter le tableau :
                // l'information des autres structures reste utile.
                LOG.warnf(e, "Signaux non lus pour la structure %s", tenant.name);
            }
        }
        // Le plus en difficulté d'abord : c'est la seule lecture utile
        // d'une liste qu'on ouvre pour agir.
        out.sort(Comparator.comparingLong(PlatformSignalDto::total).reversed());
        audit.event(AuditEventType.CROSS_TENANT_ACCESS)
                .actorEmail(actorEmail)
                .target("platform_signals", "all", "Toutes les structures")
                .description("Lecture des signaux de la plateforme")
                .record();
        return out;
    }

    private PlatformSignalDto signalsOf(TenantEntity tenant) {
        MongoDatabase db = mongoClient.getDatabase(tenant.databaseName);
        Instant staleBefore = Instant.now().minus(STALE_AFTER_DAYS, ChronoUnit.DAYS);

        long blocked = count(db, "intake_notes", Filters.and(
                Filters.eq("status", "TO_ACCOUNT"),
                Filters.or(Filters.exists("delegateSupplierId", false),
                        Filters.eq("delegateSupplierId", null))));

        long waiting = count(db, "intake_notes", Filters.and(
                Filters.eq("status", "TO_ACCOUNT"),
                Filters.lt("createdAt", staleBefore)));

        long incomplete = count(db, "intake_notes", Filters.and(
                Filters.eq("status", "ACCOUNTED"),
                Filters.exists("skippedRows", true),
                Filters.ne("skippedRows", List.of())));

        long receiptsOffCampaign = count(db, "producer_purchases",
                Filters.or(Filters.exists("campaignId", false), Filters.eq("campaignId", null)));

        // Un import qui n'a rien créé et tout refusé : le fichier n'est
        // pas passé, et sans ce compteur personne ne le saura.
        long failedImports = count(db, "import_runs", Filters.and(
                Filters.gt("at", staleBefore),
                Filters.eq("rowsCreated", 0),
                Filters.gt("rowsRejected", 0)));

        return new PlatformSignalDto(
                tenant.id, tenant.name, blocked, waiting, incomplete,
                receiptsOffCampaign, failedImports);
    }

    private long count(MongoDatabase db, String collection, org.bson.conversions.Bson filter) {
        try {
            return db.getCollection(collection).countDocuments(filter);
        } catch (RuntimeException e) {
            // Une collection absente est le cas normal d'une structure
            // qui n'exerce pas cette activité : zéro, pas une erreur.
            return 0;
        }
    }
}
