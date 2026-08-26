package com.ntech.cabosse.shared.storage;

import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.i18n.Messages;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Optional;

/**
 * Implémentation {@link FileStorage} sur filesystem local. MVP / dev.
 *
 * <p>Tous les fichiers vivent sous {@code application.file-storage.local.base-path}.
 * {@link #publicSignedUrl} retourne {@code Optional.empty()} parce que
 * le filesystem local n'a pas de mécanisme natif d'URL pré-signée — les
 * fichiers sont servis par le proxy app authentifié
 * ({@code GET /api/v1/admin/tenants/{id}/logo} pour les logos, etc.).</p>
 */
@ApplicationScoped
public class LocalFileStorage implements FileStorage {

    @Inject
    FileStorageConfig config;

    @Inject
    Logger log;

    @Override
    public String backendId() {
        return "local";
    }

    @Override
    public String store(InputStream content, long sizeBytes, String relativePath) {
        Path target = resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            try (var out = Files.newOutputStream(target,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                content.transferTo(out);
            }
            log.debugf("Local storage : écrit %s (%d octets)", target, sizeBytes);
            return relativePath;
        } catch (IOException e) {
            throw new BusinessException(Messages.msg("m.shr-local-write-failed", e.getMessage()), e);
        }
    }

    @Override
    public InputStream open(String storagePath) {
        Path source = resolve(storagePath);
        try {
            return Files.newInputStream(source, StandardOpenOption.READ);
        } catch (IOException e) {
            throw new BusinessException(Messages.msg("m.shr-file-unreadable", storagePath), e);
        }
    }

    @Override
    public void delete(String storagePath) {
        Path target = resolve(storagePath);
        try {
            Files.deleteIfExists(target);
            log.debugf("Local storage : supprimé %s", target);
        } catch (IOException e) {
            // Log mais ne propage pas — un fichier introuvable n'est pas une erreur métier.
            log.warnf(e, "Échec de suppression du fichier local %s", target);
        }
    }

    @Override
    public Optional<URI> publicSignedUrl(String storagePath, Duration ttl) {
        // Backend local : pas d'URL signée native. Le frontend passe par
        // l'endpoint proxy app (`GET /api/v1/admin/tenants/{id}/logo`) qui
        // authentifie + sert le binaire.
        return Optional.empty();
    }

    private Path resolve(String storagePath) {
        Path base = Paths.get(config.local().basePath()).toAbsolutePath().normalize();
        Path resolved = base.resolve(storagePath).normalize();
        // Garde anti path-traversal : le path résolu doit rester sous la base.
        if (!resolved.startsWith(base)) {
            throw new BusinessException(Messages.msg("m.shr-path-traversal", storagePath));
        }
        return resolved;
    }
}
