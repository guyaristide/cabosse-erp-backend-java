package com.ntech.cabosse.shared.storage;

import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Pièces jointes d'une opération, indépendamment de ce qu'elle est.
 *
 * <p>Une avance et un prêt se justifient de la même façon : une demande
 * signée, une décision, une pièce d'identité, un reçu. Le mécanisme est le
 * même, seul le propriétaire change ; il vit donc ici plutôt que recopié
 * dans chaque service métier.</p>
 */
@ApplicationScoped
public class AttachmentService {

    /** Type d'upload commun aux pièces d'un financement (PDF, PNG, JPEG). */
    public static final String FINANCING_TYPE = "financing.attachment";

    private static final CloudFileScope SCOPE = CloudFileScope.TENANT;

    @Inject FileUploadService uploads;
    @Inject JsonWebToken jwt;

    /**
     * Dépose un fichier et renvoie la référence à pousser sur l'entité.
     *
     * @param ownerType type d'entité propriétaire, pour le chemin de stockage
     */
    public AttachmentRef store(byte[] bytes, String mimeType, String originalName,
                               String label, UUID ownerId, String ownerType) {
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException("Aucun fichier fourni.");
        }
        String name = originalName != null && !originalName.isBlank()
                ? originalName.trim() : "piece-jointe";
        CloudFileEntity file = uploads.upload(
                SCOPE, bytes, mimeType, name, FINANCING_TYPE, ownerId, ownerType);

        AttachmentRef ref = new AttachmentRef();
        ref.fileId = file.id;
        ref.fileName = name;
        ref.mimeType = file.mimeType;
        ref.sizeBytes = file.sizeBytes;
        ref.label = label != null && !label.isBlank() ? label.trim() : null;
        ref.uploadedAt = Instant.now();
        ref.uploadedByEmail = actor();
        return ref;
    }

    /** Archive le binaire d'une pièce retirée de son entité. */
    public void discard(AttachmentRef ref) {
        if (ref != null && ref.fileId != null) uploads.archive(SCOPE, ref.fileId);
    }

    /** Contenu d'une pièce, pour le servir en téléchargement. */
    public AttachmentStream open(List<AttachmentRef> attachments, UUID fileId) {
        AttachmentRef ref = find(attachments, fileId);
        CloudFileEntity file = uploads.findById(SCOPE, ref.fileId);
        InputStream content = uploads.open(SCOPE, file.id);
        return new AttachmentStream(content, file.mimeType, file.sizeBytes,
                ref.fileName != null ? ref.fileName : file.originalFileName);
    }

    /**
     * Retrouve une pièce dans la liste de son entité. Passer par la liste
     * plutôt que par l'identifiant seul évite qu'un fichier d'une autre
     * opération soit servi à qui connaît son identifiant.
     */
    public AttachmentRef find(List<AttachmentRef> attachments, UUID fileId) {
        if (attachments == null) throw new NotFoundException("Pièce jointe introuvable.");
        return attachments.stream()
                .filter(a -> a.fileId != null && a.fileId.equals(fileId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException(
                        "Pièce jointe " + fileId + " introuvable sur cette opération."));
    }

    private String actor() {
        try { return jwt.getName(); } catch (RuntimeException e) { return null; }
    }

    public record AttachmentStream(InputStream content, String mimeType,
                                   long sizeBytes, String fileName) {}
}
