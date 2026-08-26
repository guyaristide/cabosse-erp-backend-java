package com.ntech.cabosse.accounting.service;

import com.ntech.cabosse.accounting.entity.FiscalYearEntity;
import com.ntech.cabosse.accounting.repository.FiscalYearRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
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
 * Pièces jointes d'un exercice (backlog CPT-12) : PV d'assemblée
 * décidant l'affectation, rapport du commissaire aux comptes. Même
 * patron que le dossier membre : binaire dans {@code cloud_files}
 * (scope TENANT), l'exercice ne porte que les métadonnées. Jointes à
 * tout moment (un PV arrive souvent après la clôture), jamais retirées
 * après clôture.
 */
@ApplicationScoped
public class FiscalYearDocumentService {

    private static final String UPLOAD_TYPE = "fiscal_year.document";
    private static final String OWNER_TYPE = "fiscal_year";
    private static final CloudFileScope SCOPE = CloudFileScope.TENANT;

    @Inject FiscalYearRepository years;
    @Inject FileUploadService uploads;
    @Inject IdGenerator idGenerator;

    public FiscalYearEntity attach(UUID yearId, String label, byte[] bytes,
                                   String mimeType, String originalName) {
        if (label == null || label.isBlank()) {
            throw new BusinessException(Messages.msg("m.acc-fy-doc-label-required"));
        }
        FiscalYearEntity e = loadOrFail(yearId);
        CloudFileEntity file = uploads.upload(
                SCOPE, bytes, mimeType,
                originalName != null ? originalName : label,
                UPLOAD_TYPE, e.id, OWNER_TYPE
        );
        FiscalYearEntity.Document doc = new FiscalYearEntity.Document();
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
        years.replace(e);
        return e;
    }

    public DocumentStream open(UUID yearId, UUID documentId) {
        FiscalYearEntity e = loadOrFail(yearId);
        FiscalYearEntity.Document doc = findDoc(e, documentId);
        CloudFileEntity file = uploads.findById(SCOPE, doc.cloudFileId);
        InputStream content = uploads.open(SCOPE, file.id);
        return new DocumentStream(content, file.mimeType, file.sizeBytes, file.originalFileName);
    }

    public FiscalYearEntity detach(UUID yearId, UUID documentId) {
        FiscalYearEntity e = loadOrFail(yearId);
        if (FiscalYearEntity.STATUS_CLOTURE.equals(e.status)) {
            throw new BusinessException(Messages.msg("m.acc-fy-doc-locked"));
        }
        FiscalYearEntity.Document doc = findDoc(e, documentId);
        uploads.archive(SCOPE, doc.cloudFileId);
        e.documents.removeIf(d -> documentId.equals(d.id));
        e.updatedAt = Instant.now();
        years.replace(e);
        return e;
    }

    public record DocumentStream(InputStream content, String mimeType,
                                 long sizeBytes, String fileName) {}

    private static FiscalYearEntity.Document findDoc(FiscalYearEntity e, UUID documentId) {
        if (e.documents == null) throw new NotFoundException(Messages.msg("m.acc-document-not-found"));
        return e.documents.stream()
                .filter(d -> documentId.equals(d.id))
                .findFirst()
                .orElseThrow(() -> new NotFoundException(Messages.msg("m.acc-document-not-found")));
    }

    private FiscalYearEntity loadOrFail(UUID id) {
        return years.findById(id)
                .orElseThrow(() -> new NotFoundException(Messages.msg("m.acc-fiscal-year-not-found", id)));
    }
}
