package com.ntech.cabosse.members.service;

import com.ntech.cabosse.members.entity.MemberEntity;
import com.ntech.cabosse.members.repository.MemberRepository;
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
 * Pièces du dossier d'adhésion d'un membre (backlog MEM-01) : attestation
 * d'exploitation, contrat, justificatifs. Même patron que la facture
 * jointe des BC : binaire dans {@code cloud_files} (scope TENANT), la
 * fiche membre ne porte que les métadonnées.
 */
@ApplicationScoped
public class MemberDocumentService {

    private static final String UPLOAD_TYPE = "member.document";
    private static final String OWNER_TYPE = "member";
    private static final CloudFileScope SCOPE = CloudFileScope.TENANT;

    @Inject MemberRepository members;
    @Inject FileUploadService uploads;
    @Inject IdGenerator idGenerator;

    public MemberEntity attach(UUID memberId, String label, byte[] bytes,
                               String mimeType, String originalName) {
        if (label == null || label.isBlank()) {
            throw new BusinessException("Libellé de la pièce requis (ex. « Attestation d'exploitation »).");
        }
        MemberEntity e = loadOrFail(memberId);
        CloudFileEntity file = uploads.upload(
                SCOPE, bytes, mimeType,
                originalName != null ? originalName : label,
                UPLOAD_TYPE, e.id, OWNER_TYPE
        );
        MemberEntity.Document doc = new MemberEntity.Document();
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
        members.replace(e);
        return e;
    }

    public DocumentStream open(UUID memberId, UUID documentId) {
        MemberEntity e = loadOrFail(memberId);
        MemberEntity.Document doc = findDoc(e, documentId);
        CloudFileEntity file = uploads.findById(SCOPE, doc.cloudFileId);
        InputStream content = uploads.open(SCOPE, file.id);
        return new DocumentStream(content, file.mimeType, file.sizeBytes, file.originalFileName);
    }

    public MemberEntity detach(UUID memberId, UUID documentId) {
        MemberEntity e = loadOrFail(memberId);
        MemberEntity.Document doc = findDoc(e, documentId);
        uploads.archive(SCOPE, doc.cloudFileId);
        e.documents.removeIf(d -> documentId.equals(d.id));
        e.updatedAt = Instant.now();
        members.replace(e);
        return e;
    }

    public record DocumentStream(InputStream content, String mimeType,
                                 long sizeBytes, String fileName) {}

    private static MemberEntity.Document findDoc(MemberEntity e, UUID documentId) {
        if (e.documents == null) throw new NotFoundException("Pièce introuvable.");
        return e.documents.stream()
                .filter(d -> documentId.equals(d.id))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Pièce introuvable."));
    }

    private MemberEntity loadOrFail(UUID id) {
        return members.findById(id)
                .orElseThrow(() -> new NotFoundException("Membre " + id + " introuvable."));
    }
}
