package com.ntech.cabosse.shared.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * Abstraction de stockage des binaires. Implémentations :
 * {@link LocalFileStorage} (MVP), {@code S3FileStorage} (Phase D+),
 * éventuellement {@code GridFsFileStorage}.
 *
 * <p>Aucun service métier n'injecte cette interface — passer par
 * {@link FileUploadService}.</p>
 */
public interface FileStorage {

    /** Identifiant du backend : "local", "s3", "gridfs". Alimente {@code CloudFileEntity.storageBackend}. */
    String backendId();

    /**
     * Persiste le contenu sous {@code relativePath}. Retourne le path
     * effectivement utilisé (peut différer si le backend ajoute un
     * suffixe pour éviter une collision — pas implémenté au MVP).
     */
    String store(InputStream content, long sizeBytes, String relativePath);

    /** Ouvre un stream de lecture. À fermer par l'appelant. */
    InputStream open(String storagePath);

    /** Suppression physique. No-op si le fichier n'existe pas. */
    void delete(String storagePath);

    /**
     * URL signée valide {@code ttl}. Vide si le backend ne supporte pas
     * l'exposition publique directe — force alors le proxy app.
     */
    Optional<URI> publicSignedUrl(String storagePath, Duration ttl);
}
