package com.ntech.cabosse.accounting.service;

import com.ntech.cabosse.accounting.entity.OdDraftEntity;
import com.ntech.cabosse.accounting.repository.OdDraftRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.storage.CloudFileEntity;
import com.ntech.cabosse.shared.storage.CloudFileScope;
import com.ntech.cabosse.shared.storage.FileUploadService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Pièces justificatives d'une opération diverse (backlog CPT-08) :
 * tableau d'amortissement, décision d'assemblée, justificatif de
 * régularisation. Même patron que le dossier membre : binaire dans
 * {@code cloud_files} (scope TENANT), l'OD ne porte que les métadonnées.
 *
 * <p>Jointes et retirées en brouillon seulement — une OD validée est
 * immuable, justificatifs compris (correction par contre-passation).
 * La lecture reste possible quel que soit le statut.</p>
 */
@ApplicationScoped
public class OdDocumentService {

    private static final String UPLOAD_TYPE = "od.document";
    private static final String OWNER_TYPE = "od_draft";
    private static final CloudFileScope SCOPE = CloudFileScope.TENANT;

    @Inject OdDraftRepository drafts;
    @Inject FileUploadService uploads;
    @Inject IdGenerator idGenerator;

    public OdDraftEntity attach(UUID odId, String label, byte[] bytes,
                                String mimeType, String originalName) {
        if (label == null || label.isBlank()) {
            throw new BusinessException("Libellé de la pièce requis (ex. « Tableau d'amortissement »).");
        }
        OdDraftEntity e = loadOrFail(odId);
        requireDraft(e);
        CloudFileEntity file = uploads.upload(
                SCOPE, bytes, mimeType,
                originalName != null ? originalName : label,
                UPLOAD_TYPE, e.id, OWNER_TYPE
        );
        OdDraftEntity.Document doc = new OdDraftEntity.Document();
        doc.id = idGenerator.newId();
        doc.label = label.trim();
        doc.fileName = file.originalFileName;
        doc.mimeType = file.mimeType;
        doc.sizeBytes = file.sizeBytes;
        doc.cloudFileId = file.id;
        doc.uploadedAt = Instant.now();
        if (e.documents == null) e.documents = new ArrayList<>();
        e.documents.add(doc);
        e.updatedAt = Instant.now();
        drafts.replace(e);
        return e;
    }

    public DocumentStream open(UUID odId, UUID documentId) {
        OdDraftEntity e = loadOrFail(odId);
        OdDraftEntity.Document doc = findDoc(e, documentId);
        CloudFileEntity file = uploads.findById(SCOPE, doc.cloudFileId);
        InputStream content = uploads.open(SCOPE, file.id);
        return new DocumentStream(content, file.mimeType, file.sizeBytes, file.originalFileName);
    }

    public OdDraftEntity detach(UUID odId, UUID documentId) {
        OdDraftEntity e = loadOrFail(odId);
        requireDraft(e);
        OdDraftEntity.Document doc = findDoc(e, documentId);
        uploads.archive(SCOPE, doc.cloudFileId);
        e.documents.removeIf(d -> documentId.equals(d.id));
        e.updatedAt = Instant.now();
        drafts.replace(e);
        return e;
    }

    public record DocumentStream(InputStream content, String mimeType,
                                 long sizeBytes, String fileName) {}

    private static void requireDraft(OdDraftEntity e) {
        if (!OdDraftEntity.STATUS_DRAFT.equals(e.status)) {
            throw new BusinessException(
                    "Une OD validée est immuable, justificatifs compris (statut actuel : "
                            + e.status + ").");
        }
    }

    private static OdDraftEntity.Document findDoc(OdDraftEntity e, UUID documentId) {
        if (e.documents == null) throw new NotFoundException("Pièce introuvable.");
        return e.documents.stream()
                .filter(d -> documentId.equals(d.id))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Pièce introuvable."));
    }

    private OdDraftEntity loadOrFail(UUID id) {
        return drafts.findById(id)
                .orElseThrow(() -> new NotFoundException("OD " + id + " introuvable."));
    }
}
