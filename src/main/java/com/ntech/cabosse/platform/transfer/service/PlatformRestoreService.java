package com.ntech.cabosse.platform.transfer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.platform.transfer.dto.PlatformArchiveManifest;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.config.ApplicationConfig;
import com.ntech.cabosse.shared.exception.ForbiddenException;
import com.ntech.cabosse.shared.exception.ValidationException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.ControlPlane;
import com.ntech.cabosse.shared.storage.FileStorage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Remonter la plateforme entière depuis une sauvegarde.
 *
 * <p>C'est le geste le plus destructeur du produit. Il remplace le plan
 * de contrôle et la base de chaque structure présente dans l'archive :
 * tout ce qui a été saisi depuis la sauvegarde est perdu, pour tous les
 * clients à la fois. Il ne se déduit d'aucun besoin courant et ne sert
 * qu'après un incident.</p>
 *
 * <p>Deux barrières, et il en faut deux. Le rôle plateforme ne suffit
 * pas : il est porté par des comptes de travail, et l'archive efface
 * précisément les comptes qui l'autorisent. S'y ajoute donc un secret
 * posé hors de l'application, sur le serveur. Absent, la restauration
 * refuse plutôt que de s'ouvrir à qui obtient un jeton.</p>
 */
@ApplicationScoped
public class PlatformRestoreService {

    @Inject MongoClient mongoClient;
    @Inject FileStorage files;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;
    @Inject ObjectMapper json;
    @Inject ApplicationConfig config;

    /** Ce que la restauration a remplacé, pour que l'écran puisse le dire. */
    public record Restored(java.time.Instant restoredAt, int tenants, long documents,
                           long filesRestored, List<String> notes) {}

    public Restored restore(Path archive, String providedSecret) {
        requireSecret(providedSecret);

        try (ZipFile zip = new ZipFile(archive.toFile())) {
            PlatformArchiveManifest manifest = readManifest(zip);
            refuseIfUnreadableFormat(manifest);
            refuseIfNewerThanServer(manifest);

            List<String> notes = new ArrayList<>();
            long documents = 0;

            // Les bases de structures d'abord : si l'une échoue, le plan de
            // contrôle n'a pas encore bougé et le serveur reste cohérent
            // avec ce qu'il servait.
            for (PlatformArchiveManifest.TenantSlice slice : manifest.tenants()) {
                // Le plan de contrôle a sa propre section : une archive
                // ancienne peut le nommer aussi comme structure, et le
                // supprimer ici effacerait ce qu'on s'apprête à rétablir.
                if (ControlPlane.DATABASE.equals(slice.databaseName())) continue;
                MongoDatabase target = mongoClient.getDatabase(slice.databaseName());
                target.drop();
                for (ZipEntry entry : entriesUnder(zip,
                        PlatformArchiveLayout.TENANTS_DIR + slice.databaseName() + "/")) {
                    String collection = collectionNameOf(entry,
                            PlatformArchiveLayout.TENANTS_DIR + slice.databaseName() + "/");
                    documents += copy(zip, entry, target.getCollection(collection));
                }
            }

            MongoDatabase control = mongoClient.getDatabase(ControlPlane.DATABASE);
            for (ZipEntry entry : entriesUnder(zip, PlatformArchiveLayout.CONTROL_DIR)) {
                String collection = collectionNameOf(entry, PlatformArchiveLayout.CONTROL_DIR);
                control.getCollection(collection).drop();
                documents += copy(zip, entry, control.getCollection(collection));
            }

            // Les sessions ouvertes n'ont plus de sens : elles ont été
            // accordées à un état que la restauration vient d'effacer, et
            // pourraient appartenir à des comptes qui n'existent plus.
            control.getCollection(ControlPlane.Collections.REFRESH_TOKENS).drop();
            notes.add("Toutes les sessions ont été fermées : chacun doit se reconnecter.");

            long restoredFiles = restoreFiles(zip, control, manifest, notes);

            audit.event(AuditEventType.DATA_EXPORTED)
                    .actorEmail(actor())
                    .target("platform_archive", "platform", "Plateforme")
                    .description("Restauration complète de la plateforme depuis une sauvegarde"
                            + " prise le " + manifest.exportedAt()
                            + " : " + manifest.tenants().size() + " structures, "
                            + documents + " documents, " + restoredFiles + " fichiers")
                    .record();

            return new Restored(java.time.Instant.now(),
                    manifest.tenants().size(), documents, restoredFiles, notes);
        } catch (IOException e) {
            throw new ValidationException(Messages.msg("m.plt-archive-unreadable"));
        }
    }

    /**
     * Le secret du serveur, comparé en temps constant.
     *
     * <p>Une comparaison qui s'arrête au premier caractère différent dit
     * combien de caractères sont justes. Sur une route qu'on peut
     * appeler en boucle, c'est de quoi retrouver le secret.</p>
     */
    private void requireSecret(String provided) {
        Optional<String> expected = config.platformArchive().restoreSecret();
        if (expected.isEmpty() || expected.get().isBlank()) {
            throw new ForbiddenException(Messages.msg("m.plt-restore-secret-absent"));
        }
        byte[] a = expected.get().getBytes(StandardCharsets.UTF_8);
        byte[] b = (provided == null ? "" : provided).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(a, b)) {
            throw new ForbiddenException(Messages.msg("m.plt-restore-secret-wrong"));
        }
    }

    private PlatformArchiveManifest readManifest(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry(PlatformArchiveLayout.MANIFEST);
        if (entry == null) {
            throw new ValidationException(Messages.msg("m.plt-archive-unreadable"));
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return json.readValue(in.readAllBytes(), PlatformArchiveManifest.class);
        }
    }

    /** Une archive d'un autre format ne se devine pas : elle se refuse. */
    private void refuseIfUnreadableFormat(PlatformArchiveManifest manifest) {
        if (manifest.archiveFormat() != PlatformArchiveLayout.FORMAT) {
            throw new ValidationException(Messages.msg("m.plt-archive-format",
                    manifest.archiveFormat(), PlatformArchiveLayout.FORMAT));
        }
    }

    /**
     * Une archive plus récente que le serveur ne se restaure pas.
     *
     * <p>Ses documents ont la forme qu'une migration postérieure leur a
     * donnée. Les charger produirait des lectures fausses, sans erreur.</p>
     */
    private void refuseIfNewerThanServer(PlatformArchiveManifest manifest) {
        String archived = manifest.migrationVersion();
        String server = lastKnownMigration();
        if (archived == null || server == null) return;
        if (archived.compareTo(server) > 0) {
            throw new ValidationException(
                    Messages.msg("m.plt-archive-too-recent", archived, server));
        }
    }

    private String lastKnownMigration() {
        Document last = mongoClient.getDatabase(ControlPlane.DATABASE)
                .getCollection("mongockChangeLog")
                .find().sort(new Document("timestamp", -1)).first();
        return last != null ? last.getString("changeId") : null;
    }

    /**
     * Repose les binaires des deux registres et réécrit leur chemin.
     *
     * <p>Le chemin d'origine appartient au serveur qui a produit
     * l'archive. Sur celui-ci, il ne désigne rien.</p>
     */
    private long restoreFiles(ZipFile zip, MongoDatabase control,
                              PlatformArchiveManifest manifest, List<String> notes) {
        Map<String, ZipEntry> binaries = new HashMap<>();
        for (ZipEntry entry : entriesUnder(zip, PlatformArchiveLayout.FILES_DIR)) {
            binaries.put(entry.getName().substring(PlatformArchiveLayout.FILES_DIR.length()), entry);
        }
        long[] tally = new long[2];

        restoreInto(zip, binaries,
                control.getCollection(ControlPlane.Collections.CLOUD_FILES), "platform", tally);
        for (PlatformArchiveManifest.TenantSlice slice : manifest.tenants()) {
            restoreInto(zip, binaries,
                    mongoClient.getDatabase(slice.databaseName())
                            .getCollection(ControlPlane.Collections.CLOUD_FILES),
                    slice.tenantId(), tally);
        }

        if (tally[1] > 0) {
            notes.add(tally[1] + " fichier(s) référencés mais absents de l'archive : "
                    + "ils manquaient déjà au serveur d'origine.");
        }
        return tally[0];
    }

    private void restoreInto(ZipFile zip, Map<String, ZipEntry> binaries,
                             MongoCollection<Document> registry, String owner, long[] tally) {
        for (Document file : registry.find()) {
            Object id = file.get("_id");
            ZipEntry entry = id == null ? null : binaries.get(id.toString());
            if (entry == null) { tally[1]++; continue; }
            try (InputStream in = zip.getInputStream(entry)) {
                byte[] bytes = in.readAllBytes();
                String path = owner + "/" + id;
                // Le stockage écrit en création stricte. Le chemin d'un
                // binaire ne dépendant que de son identifiant, une
                // seconde restauration de la même sauvegarde butait sur
                // le fichier posé par la première et s'arrêtait au
                // milieu, bases déjà remplacées (relevé le 23/09/2026).
                // Remonter un serveur deux fois de suite est le cas
                // normal quand la première tentative a mal tourné.
                try {
                    files.delete(path);
                } catch (RuntimeException absent) {
                    // Rien à retirer : c'est le cas courant.
                }
                files.store(new ByteArrayInputStream(bytes), bytes.length, path);
                registry.updateOne(Filters.eq("_id", id),
                        new Document("$set", new Document("storagePath", path)
                                .append("storageBackend", files.backendId())));
                tally[0]++;
            } catch (IOException unreadable) {
                tally[1]++;
            }
        }
    }

    private long copy(ZipFile zip, ZipEntry entry,
                      MongoCollection<Document> destination) throws IOException {
        List<Document> batch = new ArrayList<>();
        long written = 0;
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                zip.getInputStream(entry), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                batch.add(Document.parse(line));
                if (batch.size() >= 500) {
                    destination.insertMany(batch); written += batch.size(); batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) { destination.insertMany(batch); written += batch.size(); }
        return written;
    }

    private List<ZipEntry> entriesUnder(ZipFile zip, String prefix) {
        List<ZipEntry> out = new ArrayList<>();
        zip.stream()
                .filter(e -> e.getName().startsWith(prefix) && !e.isDirectory())
                // Une entrée plus profonde appartient à une autre tranche :
                // la prendre ici mêlerait deux structures dans une base.
                .filter(e -> !e.getName().substring(prefix.length()).contains("/"))
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
}
