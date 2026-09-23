package com.ntech.cabosse.tenant.transfer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.uuid.UuidCreator;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.health.BuildInfo;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.ConflictException;
import com.ntech.cabosse.shared.exception.ValidationException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.ControlPlane;
import com.ntech.cabosse.shared.storage.FileStorage;
import com.ntech.cabosse.tenant.transfer.dto.TenantArchiveManifest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.UuidRepresentation;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Rejouer une archive comme structure neuve (backlog SAAS-21).
 *
 * <p>La restauration <strong>crée</strong> : nouvel identifiant, nouvelle
 * base, nouveau raccourci. C'est ce que demandent les deux usages qui
 * l'ont fait naître, vérifier une sauvegarde en local et déplacer un
 * client d'un serveur à l'autre. Écraser une structure vivante est un
 * geste différent, qui ne se glisse pas dans celui-ci.</p>
 *
 * <p>Les identifiants de documents sont <strong>conservés</strong>, sauf
 * celui du tenant : les données se renvoient entre elles, et les
 * renuméroter casserait chaque rattachement. Un justificatif est désigné
 * par l'identifiant de son fichier, une écriture par celui de son
 * auteur ; les renuméroter rendrait les pièces jointes introuvables et
 * les auteurs inconnus.</p>
 *
 * <p>Conséquence assumée : <strong>une structure ne se restaure pas sur
 * un serveur qui la détient déjà</strong>. Les collections du plan de
 * contrôle sont communes à toutes les structures, ses comptes et ses
 * fichiers s'y heurteraient. Le cas est détecté avant la moindre
 * écriture et refusé en le nommant, plutôt que de laisser une
 * restauration à moitié faite.</p>
 */
@ApplicationScoped
public class TenantRestoreService {

    @Inject MongoClient mongoClient;
    @Inject FileStorage files;
    @Inject BuildInfo buildInfo;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;
    @Inject ObjectMapper json;

    /** Ce que la restauration a produit, pour que l'écran puisse le dire. */
    public record Restored(UUID tenantId, String slug, String databaseName,
                           long documents, long filesRestored, List<String> notes) {}

    public Restored restore(Path archive, String targetSlug) {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            TenantArchiveManifest manifest = readManifest(zip);
            refuseIfNewerThanServer(manifest);

            UUID newTenantId = UuidCreator.getTimeOrderedEpoch();
            String slug = (targetSlug == null || targetSlug.isBlank())
                    ? manifest.tenantSlug() + "-restaure" : targetSlug.trim();
            String databaseName = "tenant_" + newTenantId.toString().replace("-", "");

            MongoDatabase control = mongoClient.getDatabase(ControlPlane.DATABASE);
            if (control.getCollection(ControlPlane.Collections.TENANTS)
                    .countDocuments(Filters.eq("slug", slug)) > 0) {
                throw new ConflictException(Messages.msg("m.tnt-archive-slug-taken", slug));
            }

            refuseIfAlreadyPresent(zip, control);

            List<String> notes = new ArrayList<>();
            long documents = 0;
            long restoredFiles;

            // Au moindre échec, on défait : une restauration à moitié
            // faite laisse une structure fantôme que personne ne saura
            // lire ni supprimer.
            try {
                // La base d'exploitation, telle quelle : ses documents ne
                // portent pas le tenant, c'est la base qui le porte.
                MongoDatabase target = mongoClient.getDatabase(databaseName);
                for (ZipEntry entry : entriesUnder(zip, TenantArchiveLayout.TENANT_DIR)) {
                    String collection = collectionNameOf(entry, TenantArchiveLayout.TENANT_DIR);
                    documents += copy(zip, entry, target.getCollection(collection), null, null);
                }

                // La tranche du plan de contrôle, réétiquetée au nouveau tenant.
                for (TenantArchiveLayout.Slice slice : TenantArchiveLayout.CONTROL_SLICES) {
                    ZipEntry entry = zip.getEntry(
                            TenantArchiveLayout.CONTROL_DIR + slice.collection() + ".jsonl");
                    if (entry == null) continue;
                    documents += copy(zip, entry, control.getCollection(slice.collection()),
                            slice, new Rebind(manifest.tenantId(), newTenantId, slug, databaseName));
                }

                restoredFiles = restoreFiles(zip, control, target, newTenantId, notes);
            } catch (RuntimeException failure) {
                undo(control, newTenantId, databaseName);
                throw failure;
            }

            audit.event(AuditEventType.DATA_EXPORTED)
                    .actorEmail(actor())
                    .target("tenant_archive", newTenantId.toString(), manifest.tenantName())
                    .tenant(newTenantId, manifest.tenantName())
                    .description("Restauration de l'archive de " + manifest.tenantName()
                            + " prise le " + manifest.exportedAt()
                            + " : " + documents + " documents, " + restoredFiles + " fichiers")
                    .record();

            return new Restored(newTenantId, slug, databaseName, documents, restoredFiles, notes);
        } catch (IOException e) {
            throw new ValidationException(Messages.msg("m.tnt-archive-unreadable"));
        }
    }

    private TenantArchiveManifest readManifest(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry(TenantArchiveLayout.MANIFEST);
        if (entry == null) {
            throw new ValidationException(Messages.msg("m.tnt-archive-unreadable"));
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return json.readValue(in.readAllBytes(), TenantArchiveManifest.class);
        }
    }

    /**
     * Une archive plus récente que le serveur ne se restaure pas.
     *
     * <p>Ses documents ont la forme qu'une migration postérieure leur a
     * donnée. Les charger ici produirait des lectures fausses, sans
     * erreur : le refus est la seule réponse honnête.</p>
     */
    private void refuseIfNewerThanServer(TenantArchiveManifest manifest) {
        String archived = manifest.migrationVersion();
        String server = lastKnownMigration();
        if (archived == null || server == null) return;
        if (archived.compareTo(server) > 0) {
            throw new ValidationException(
                    Messages.msg("m.tnt-archive-too-recent", archived, server));
        }
    }

    /** La migration la plus avancée que ce serveur sache appliquer. */
    private String lastKnownMigration() {
        MongoDatabase control = mongoClient.getDatabase(ControlPlane.DATABASE);
        Document last = control.getCollection("mongockChangeLog")
                .find().sort(new Document("timestamp", -1)).first();
        return last != null ? last.getString("changeId") : null;
    }

    /** Ce qui doit être réétiqueté au nouveau tenant. */
    private record Rebind(UUID oldTenantId, UUID newTenantId, String slug, String databaseName) {}

    private long copy(ZipFile zip, ZipEntry entry,
                      com.mongodb.client.MongoCollection<Document> destination,
                      TenantArchiveLayout.Slice slice, Rebind rebind) throws IOException {
        List<Document> batch = new ArrayList<>();
        long written = 0;
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                zip.getInputStream(entry), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                Document d = Document.parse(line);
                if (rebind != null) rebind(d, slice, rebind);
                batch.add(d);
                if (batch.size() >= 500) {
                    destination.insertMany(batch); written += batch.size(); batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) { destination.insertMany(batch); written += batch.size(); }
        return written;
    }

    /** Réétiquette un document du plan de contrôle sur le tenant neuf. */
    private void rebind(Document d, TenantArchiveLayout.Slice slice, Rebind rebind) {
        if ("tenants".equals(slice.collection())) {
            d.put("_id", rebind.newTenantId());
            d.put("slug", rebind.slug());
            d.put("databaseName", rebind.databaseName());
        } else {
            d.put(slice.tenantField(), rebind.newTenantId());
        }
    }

    /**
     * Repose les binaires, et réécrit leur chemin.
     *
     * <p>Le chemin d'origine appartient au serveur qui a produit
     * l'archive : le reprendre tel quel ferait pointer la nouvelle
     * structure sur des fichiers qui ne sont pas les siens, ou sur
     * rien.</p>
     */
    private long restoreFiles(ZipFile zip, MongoDatabase control, MongoDatabase target,
                              UUID newTenantId, List<String> notes) throws IOException {
        Map<String, ZipEntry> binaries = new HashMap<>();
        for (ZipEntry entry : entriesUnder(zip, TenantArchiveLayout.FILES_DIR)) {
            binaries.put(entry.getName().substring(TenantArchiveLayout.FILES_DIR.length()), entry);
        }
        long[] tally = new long[2];

        // Les deux registres, comme à l'export. Celui du plan de contrôle
        // porte le logo, réétiqueté au tenant neuf par le passage
        // précédent ; celui de la base porte les pièces métier, qui
        // reviennent avec leurs identifiants d'origine.
        restoreInto(zip, binaries, control.getCollection(ControlPlane.Collections.CLOUD_FILES),
                Filters.eq("tenantId", newTenantId), newTenantId, tally);
        restoreInto(zip, binaries, target.getCollection(ControlPlane.Collections.CLOUD_FILES),
                new Document(), newTenantId, tally);

        if (tally[1] > 0) {
            notes.add(tally[1] + " fichier(s) référencés mais absents de l'archive : "
                    + "ils manquaient déjà au serveur d'origine.");
        }
        return tally[0];
    }

    /**
     * Repose les binaires d'un registre et réécrit leur chemin.
     *
     * <p>Le chemin d'origine appartient au serveur qui a produit
     * l'archive. Le laisser en place ferait pointer la structure neuve
     * sur les fichiers d'une autre, ou sur rien.</p>
     */
    private void restoreInto(ZipFile zip, Map<String, ZipEntry> binaries,
                             com.mongodb.client.MongoCollection<Document> registry,
                             org.bson.conversions.Bson filter, UUID newTenantId,
                             long[] tally) throws IOException {
        for (Document file : registry.find(filter)) {
            Object id = file.get("_id");
            ZipEntry entry = id == null ? null : binaries.get(id.toString());
            if (entry == null) { tally[1]++; continue; }
            try (InputStream in = zip.getInputStream(entry)) {
                byte[] bytes = in.readAllBytes();
                String path = files.store(new ByteArrayInputStream(bytes), bytes.length,
                        newTenantId + "/" + id);
                registry.updateOne(Filters.eq("_id", id),
                        new Document("$set", new Document("storagePath", path)
                                .append("storageBackend", files.backendId())));
                tally[0]++;
            }
        }
    }

    private List<ZipEntry> entriesUnder(ZipFile zip, String prefix) {
        List<ZipEntry> out = new ArrayList<>();
        zip.stream().filter(e -> e.getName().startsWith(prefix) && !e.isDirectory())
                .forEach(out::add);
        return out;
    }

    private String collectionNameOf(ZipEntry entry, String prefix) {
        String name = entry.getName().substring(prefix.length());
        return name.endsWith(".jsonl") ? name.substring(0, name.length() - 6) : name;
    }

    private String actor() {
        return jwt == null ? null : jwt.getName();
    }

    /**
     * Refuse avant d'écrire si un document de l'archive est déjà là.
     *
     * <p>Les identifiants sont conservés pour que les pièces jointes et
     * les auteurs restent désignables. Sur un serveur qui détient déjà
     * cette structure, ils se heurtent. Mieux vaut le dire avant que de
     * s'arrêter au milieu.</p>
     */
    private void refuseIfAlreadyPresent(ZipFile zip, MongoDatabase control) throws IOException {
        for (TenantArchiveLayout.Slice slice : TenantArchiveLayout.CONTROL_SLICES) {
            ZipEntry entry = zip.getEntry(
                    TenantArchiveLayout.CONTROL_DIR + slice.collection() + ".jsonl");
            if (entry == null) continue;
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    zip.getInputStream(entry), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    Object id = Document.parse(line).get("_id");
                    if (id == null) continue;
                    if ("tenants".equals(slice.collection())) continue; // celui-là change d'identité
                    if (control.getCollection(slice.collection())
                            .countDocuments(Filters.eq("_id", id)) > 0) {
                        throw new ConflictException(Messages.msg(
                                "m.tnt-archive-already-here", slice.collection()));
                    }
                }
            }
        }
    }

    /** Défait une restauration interrompue, pour ne rien laisser derrière. */
    private void undo(MongoDatabase control, UUID tenantId, String databaseName) {
        try {
            for (TenantArchiveLayout.Slice slice : TenantArchiveLayout.CONTROL_SLICES) {
                String field = "tenants".equals(slice.collection()) ? "_id" : slice.tenantField();
                control.getCollection(slice.collection())
                        .deleteMany(Filters.eq(field, tenantId));
            }
            mongoClient.getDatabase(databaseName).drop();
        } catch (RuntimeException ignored) {
            // Le nettoyage ne doit pas masquer la cause d'origine.
        }
    }

}
