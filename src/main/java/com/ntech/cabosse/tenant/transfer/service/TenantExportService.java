package com.ntech.cabosse.tenant.transfer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.health.BuildInfo;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.ControlPlane;
import com.ntech.cabosse.shared.storage.FileStorage;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.tenant.repository.TenantRepository;
import com.ntech.cabosse.tenant.transfer.dto.TenantArchiveManifest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.json.JsonWriterSettings;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * L'archive complète d'un tenant (backlog SAAS-20).
 *
 * <p>Une coopérative ne vit pas dans une seule base : sa base
 * d'exploitation, sa tranche du plan de contrôle (fiche, comptes,
 * abonnement, tickets, audit, métadonnées de fichiers) et les binaires
 * eux-mêmes. Emporter la base seule rendrait une sauvegarde sans comptes
 * ni pièces jointes, donc inexploitable.</p>
 *
 * <p>Écriture <strong>en flux vers un fichier temporaire</strong>, jamais
 * en mémoire : un tenant avec quelques milliers de justificatifs ferait
 * tomber un serveur qui sert toutes les structures. L'appelant reçoit le
 * chemin et le supprime après envoi.</p>
 */
@ApplicationScoped
public class TenantExportService {

    /** Documents écrits en relâché : l'archive se relit, elle ne se rejoue pas en base. */
    private static final JsonWriterSettings JSON = JsonWriterSettings.builder().build();

    @Inject MongoClient mongoClient;
    @Inject TenantRepository tenants;
    @Inject FileStorage files;
    @Inject BuildInfo buildInfo;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;
    @Inject ObjectMapper json;

    /** Le chemin de l'archive et son manifeste, pour que l'appelant sache quoi servir. */
    public record Archive(Path path, TenantArchiveManifest manifest) {}

    public Archive export(UUID tenantId) {
        TenantEntity tenant = tenants.findById(tenantId);
        if (tenant == null) {
            throw new NotFoundException(Messages.msg("m.tnt-not-found", tenantId));
        }

        List<TenantArchiveManifest.CollectionCount> tenantCollections = new ArrayList<>();
        List<TenantArchiveManifest.CollectionCount> controlSlices = new ArrayList<>();
        long filesCount = 0;
        long filesBytes = 0;

        try {
            Path target = Files.createTempFile("tenant-" + tenant.slug + "-", ".zip");
            try (OutputStream raw = Files.newOutputStream(target);
                 ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(raw))) {

                MongoDatabase tenantDb = mongoClient.getDatabase(tenant.databaseName);
                for (String name : tenantDb.listCollectionNames()) {
                    long written = writeCollection(zip,
                            TenantArchiveLayout.TENANT_DIR + name + ".jsonl",
                            tenantDb.getCollection(name).find());
                    tenantCollections.add(new TenantArchiveManifest.CollectionCount(name, written));
                }

                MongoDatabase control = mongoClient.getDatabase(ControlPlane.DATABASE);
                for (TenantArchiveLayout.Slice slice : TenantArchiveLayout.CONTROL_SLICES) {
                    long written = writeCollection(zip,
                            TenantArchiveLayout.CONTROL_DIR + slice.collection() + ".jsonl",
                            control.getCollection(slice.collection())
                                    .find(Filters.eq(slice.tenantField(), tenantId)));
                    controlSlices.add(
                            new TenantArchiveManifest.CollectionCount(slice.collection(), written));
                }

                // Les binaires, nommés par leur identifiant de fichier : le
                // chemin de stockage est propre au serveur d'origine et ne
                // vaudra plus rien sur celui d'arrivée.
                for (Document file : control.getCollection(ControlPlane.Collections.CLOUD_FILES)
                        .find(Filters.eq("tenantId", tenantId))) {
                    String storagePath = file.getString("storagePath");
                    Object id = file.get("_id");
                    if (storagePath == null || id == null) continue;
                    try (InputStream content = files.open(storagePath)) {
                        zip.putNextEntry(new ZipEntry(TenantArchiveLayout.FILES_DIR + id));
                        filesBytes += content.transferTo(zip);
                        zip.closeEntry();
                        filesCount++;
                    } catch (Exception missing) {
                        // Un binaire absent ne fait pas échouer l'archive : il
                        // manquait déjà avant elle, et le manifeste dira que le
                        // compte ne tombe pas juste.
                    }
                }

                TenantArchiveManifest manifest = new TenantArchiveManifest(
                        TenantArchiveLayout.FORMAT,
                        tenant.id, tenant.slug, tenant.name, tenant.databaseName,
                        Instant.now(), actor(),
                        buildInfo.version(), buildInfo.commit(),
                        lastMigration(tenantDb),
                        tenantCollections, controlSlices,
                        filesCount, filesBytes,
                        TenantArchiveLayout.EXCLUDED);

                zip.putNextEntry(new ZipEntry(TenantArchiveLayout.MANIFEST));
                zip.write(json.writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(manifest));
                zip.closeEntry();

                audit.event(AuditEventType.DATA_EXPORTED)
                        .actorEmail(actor())
                        .target("tenant_archive", tenant.id.toString(), tenant.name)
                        .tenant(tenant.id, tenant.name)
                        .description("Export complet de la structure " + tenant.name
                                + " : " + tenantCollections.size() + " collections, "
                                + filesCount + " fichiers")
                        .record();

                return new Archive(target, manifest);
            }
        } catch (IOException e) {
            throw new IllegalStateException(Messages.msg("m.tnt-archive-failed", tenant.name), e);
        }
    }

    /** Écrit une collection en JSON ligne à ligne, et rend le nombre de documents. */
    private long writeCollection(ZipOutputStream zip, String entry,
                                 Iterable<Document> documents) throws IOException {
        zip.putNextEntry(new ZipEntry(entry));
        long count = 0;
        for (Document d : documents) {
            zip.write(d.toJson(JSON).getBytes(StandardCharsets.UTF_8));
            zip.write('\n');
            count++;
        }
        zip.closeEntry();
        return count;
    }

    /**
     * La dernière migration appliquée à cette base.
     *
     * <p>C'est elle que la restauration compare : restaurer sur un serveur
     * plus ancien produirait des documents que le code ne sait pas lire.</p>
     */
    private String lastMigration(MongoDatabase db) {
        Document last = db.getCollection("mongockChangeLog")
                .find().sort(new Document("timestamp", -1)).first();
        return last != null ? last.getString("changeId") : null;
    }

    private String actor() {
        return jwt == null ? null : jwt.getName();
    }
}
