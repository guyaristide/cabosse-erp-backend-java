package com.ntech.cabosse.importjournal.service;

import com.ntech.cabosse.importjournal.dto.ImportFileDto;
import com.ntech.cabosse.importjournal.entity.ImportFileEntity;
import com.ntech.cabosse.importjournal.repository.ImportFileRepository;
import com.ntech.cabosse.shared.api.PageRequest;
import com.ntech.cabosse.shared.api.Pagination;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.storage.CloudFileEntity;
import com.ntech.cabosse.shared.storage.CloudFileScope;
import com.ntech.cabosse.shared.storage.FileUploadService;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.io.InputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Garde le fichier déposé à un import (DEC-52) : écrit tel quel dans le
 * registre des fichiers de la structure, relié au domaine et au journal.
 */
@ApplicationScoped
public class ImportFileService {

    static final String FILE_TYPE = "import.source";
    static final String OWNER_TYPE = "import_file";

    @Inject ImportFileRepository files;
    @Inject FileUploadService uploads;
    @Inject IdGenerator idGenerator;
    @Inject TenantContext tenantContext;
    @Inject AuditService audit;
    @Inject JsonWebToken jwt;

    /** Ce qu'un dépôt porte : le fichier et le contexte de l'import qui vient d'avoir lieu. */
    public record Deposit(byte[] content, String mimeType, String fileName, String domain,
                          UUID importRunId, Integer rowsCreated, Integer rowsRejected) {}

    public ImportFileDto keep(Deposit d) {
        if (d.content() == null || d.content().length == 0) {
            throw new BusinessException(Messages.msg("m.impf-file-required"));
        }
        if (d.domain() == null || d.domain().isBlank()) {
            throw new BusinessException(Messages.msg("m.impf-domain-required"));
        }
        ImportFileEntity e = new ImportFileEntity();
        e.id = idGenerator.newId();
        String mime = d.mimeType() == null || d.mimeType().isBlank()
                ? "application/octet-stream" : d.mimeType();
        // Le binaire va dans la base de la structure : l'archive l'emporte.
        CloudFileEntity stored = uploads.upload(CloudFileScope.TENANT, d.content(), mime,
                d.fileName(), FILE_TYPE, e.id, OWNER_TYPE);
        e.domain = d.domain().trim();
        e.fileName = d.fileName();
        e.mimeType = mime;
        e.sizeBytes = d.content().length;
        e.cloudFileId = stored.id;
        e.importRunId = d.importRunId();
        e.rowsCreated = d.rowsCreated();
        e.rowsRejected = d.rowsRejected();
        e.uploadedAt = Instant.now();
        e.uploadedByEmail = actor();
        files.insert(e);

        Map<String, Object> payload = new HashMap<>();
        payload.put("domain", e.domain);
        payload.put("fileName", e.fileName);
        payload.put("sizeBytes", e.sizeBytes);
        payload.put("importRunId", e.importRunId != null ? e.importRunId.toString() : null);
        audit.event(AuditEventType.ATTACHMENT_ADDED)
                .actorEmail(actor())
                .target(OWNER_TYPE, e.id.toString(), e.fileName)
                .tenant(tenantContext.tenantId(), null)
                .description("Fichier d'import conservé : " + e.fileName + " (" + e.domain + ")")
                .payload(payload)
                .record();
        return ImportFileDto.from(e);
    }

    public Pagination<ImportFileDto> page(String domain, PageRequest pr) {
        long total = files.count(domain);
        List<ImportFileDto> items = files.search(domain, pr.skip(), pr.perPage()).stream()
                .map(ImportFileDto::from).toList();
        Map<String, String> filters = new HashMap<>();
        if (domain != null && !domain.isBlank()) filters.put("domain", domain.trim());
        return Pagination.of(total, pr, new String[]{"uploadedAt"}, "desc", filters, items);
    }

    /** Le fichier tel qu'il a été déposé, pour le retélécharger. */
    public record Content(ImportFileEntity file, InputStream stream) {}

    public Content open(UUID id) {
        ImportFileEntity e = files.findById(id).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.impf-not-found", id)));
        return new Content(e, uploads.open(CloudFileScope.TENANT, e.cloudFileId));
    }

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }
}
