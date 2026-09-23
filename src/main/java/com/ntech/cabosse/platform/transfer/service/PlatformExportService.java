package com.ntech.cabosse.platform.transfer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.ntech.cabosse.health.BuildInfo;
import com.ntech.cabosse.platform.transfer.dto.PlatformArchiveManifest;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.ControlPlane;
import com.ntech.cabosse.shared.storage.FileStorage;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * La sauvegarde de toute la plateforme.
 *
 * <p>L'archive d'une structure sert à déplacer un client. Celle-ci sert à
 * remonter un serveur : plan de contrôle entier, base de chaque
 * structure, et tous les binaires des deux registres. Rien n'est filtré,
 * parce qu'une sauvegarde qui choisit ce qu'elle garde ne répond plus de
 * ce qu'elle rend.</p>
 *
 * <p>Écriture <strong>en flux vers un fichier temporaire</strong>, jamais
 * en mémoire : le contenu complet du serveur ne tient pas dans le tas.
 * L'appelant reçoit le chemin et le supprime après envoi.</p>
 */
@ApplicationScoped
public class PlatformExportService {

    /** Documents écrits en relâché : l'archive se relit, elle ne se rejoue pas en base. */
    private static final JsonWriterSettings JSON = JsonWriterSettings.builder().build();

    @Inject MongoClient mongoClient;
    @Inject FileStorage files;
    @Inject BuildInfo buildInfo;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;
    @Inject ObjectMapper json;

    /** Le chemin de l'archive et son manifeste, pour que l'appelant sache quoi servir. */
    public record Archive(Path path, PlatformArchiveManifest manifest) {}

    public Archive export() {
        List<PlatformArchiveManifest.CollectionCount> controlPlane = new ArrayList<>();
        List<PlatformArchiveManifest.TenantSlice> tenantSlices = new ArrayList<>();
        Set<String> binariesWritten = new HashSet<>();
        long filesCount = 0;
        long filesBytes = 0;

        try {
            Path target = Files.createTempFile("cabosse-platform-", ".zip");
            try (OutputStream raw = Files.newOutputStream(target);
                 ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(raw))) {

                MongoDatabase control = mongoClient.getDatabase(ControlPlane.DATABASE);
                for (String name : control.listCollectionNames()) {
                    if (ControlPlane.Collections.REFRESH_TOKENS.equals(name)) continue;
                    long written = writeCollection(zip,
                            PlatformArchiveLayout.CONTROL_DIR + name + ".jsonl",
                            control.getCollection(name).find());
                    controlPlane.add(new PlatformArchiveManifest.CollectionCount(name, written));
                }

                // Chaque structure, par sa base. On part de la liste des
                // structures et non des bases du serveur : une base
                // orpheline, restaurée, ressusciterait un client supprimé.
                for (Document tenant : control.getCollection(ControlPlane.Collections.TENANTS).find()) {
                    String databaseName = tenant.getString("databaseName");
                    if (databaseName == null) continue;
                    // La structure qui représente l'éditeur pointe sur le
                    // plan de contrôle lui-même. L'emporter ici le
                    // dupliquerait dans l'archive, et la restauration
                    // effacerait le plan de contrôle au titre d'une
                    // structure, avant même de l'avoir rétabli.
                    if (ControlPlane.DATABASE.equals(databaseName)) continue;
                    MongoDatabase db = mongoClient.getDatabase(databaseName);

                    List<PlatformArchiveManifest.CollectionCount> collections = new ArrayList<>();
                    for (String name : db.listCollectionNames()) {
                        long written = writeCollection(zip,
                                PlatformArchiveLayout.TENANTS_DIR + databaseName + "/" + name + ".jsonl",
                                db.getCollection(name).find());
                        collections.add(new PlatformArchiveManifest.CollectionCount(name, written));
                    }
                    tenantSlices.add(new PlatformArchiveManifest.TenantSlice(
                            String.valueOf(tenant.get("_id")), tenant.getString("slug"),
                            tenant.getString("name"), databaseName, collections));

                    long[] tally = writeBinaries(zip, db, binariesWritten);
                    filesCount += tally[0];
                    filesBytes += tally[1];
                }

                long[] platformTally = writeBinaries(zip, control, binariesWritten);
                filesCount += platformTally[0];
                filesBytes += platformTally[1];

                PlatformArchiveManifest manifest = new PlatformArchiveManifest(
                        PlatformArchiveLayout.FORMAT,
                        Instant.now(), actor(),
                        buildInfo.version(), buildInfo.commit(),
                        lastMigration(control),
                        controlPlane, tenantSlices,
                        filesCount, filesBytes,
                        PlatformArchiveLayout.EXCLUDED);

                zip.putNextEntry(new ZipEntry(PlatformArchiveLayout.MANIFEST));
                zip.write(json.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
                zip.closeEntry();

                audit.event(AuditEventType.DATA_EXPORTED)
                        .actorEmail(actor())
                        .target("platform_archive", "platform", "Plateforme")
                        .description("Sauvegarde complète de la plateforme : "
                                + tenantSlices.size() + " structures, "
                                + filesCount + " fichiers")
                        .record();

                return new Archive(target, manifest);
            }
        } catch (IOException e) {
            throw new IllegalStateException(Messages.msg("m.plt-archive-failed"), e);
        }
    }

    /**
     * Les binaires inscrits dans un registre, nommés par leur identifiant.
     *
     * <p>Le chemin de stockage appartient au serveur d'origine et ne vaut
     * plus rien ailleurs. L'identifiant, lui, est porté par la fiche du
     * fichier, qui voyage avec l'archive.</p>
     *
     * <p>{@code alreadyWritten} évite d'écrire deux fois le même binaire :
     * un zip refuse deux entrées de même nom, et l'export échouerait sur
     * un doublon au lieu de rendre une archive.</p>
     */
    private long[] writeBinaries(ZipOutputStream zip, MongoDatabase database,
                                 Set<String> alreadyWritten) {
        long count = 0;
        long bytes = 0;
        for (Document file : database.getCollection(ControlPlane.Collections.CLOUD_FILES).find()) {
            String storagePath = file.getString("storagePath");
            Object id = file.get("_id");
            if (storagePath == null || id == null) continue;
            if (!alreadyWritten.add(id.toString())) continue;
            try (InputStream content = files.open(storagePath)) {
                zip.putNextEntry(new ZipEntry(PlatformArchiveLayout.FILES_DIR + id));
                bytes += content.transferTo(zip);
                zip.closeEntry();
                count++;
            } catch (Exception missing) {
                // Un binaire absent ne fait pas échouer la sauvegarde : il
                // manquait déjà avant elle, et le manifeste dira que le
                // compte ne tombe pas juste.
            }
        }
        return new long[] {count, bytes};
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

    private String lastMigration(MongoDatabase db) {
        Document last = db.getCollection("mongockChangeLog")
                .find().sort(new Document("timestamp", -1)).first();
        return last != null ? last.getString("changeId") : null;
    }

    private String actor() {
        return jwt == null ? null : jwt.getName();
    }
}
