package com.ntech.cabosse.achats.service;

import com.ntech.cabosse.achats.entity.PurchaseOrderEntity;
import com.ntech.cabosse.achats.repository.PurchaseOrderRepository;
import com.ntech.cabosse.shared.audit.AuditEventType;
import com.ntech.cabosse.shared.audit.AuditService;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.storage.CloudFileEntity;
import com.ntech.cabosse.shared.storage.CloudFileScope;
import com.ntech.cabosse.shared.storage.FileUploadService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;

/**
 * Gestion de la facture fournisseur jointe à un BC.
 *
 * <p>Mêmes principes que {@code ArticleImageService} :</p>
 * <ul>
 *   <li>Le binaire vit dans {@code <tenant_db>.cloud_files} (scope TENANT) ;</li>
 *   <li>L'entité BC ne porte que {@code attachmentFileId} ;</li>
 *   <li>Type d'upload : {@code "purchase_order.attachment"} —
 *       JPEG/PNG/PDF ≤ 10 MB.</li>
 * </ul>
 */
@ApplicationScoped
public class PurchaseOrderAttachmentService {

    private static final String UPLOAD_TYPE = "purchase_order.attachment";
    private static final String OWNER_TYPE = "purchase_order";
    private static final CloudFileScope SCOPE = CloudFileScope.TENANT;

    @Inject PurchaseOrderRepository orders;
    @Inject FileUploadService uploads;
    @Inject AuditService audit;
    @Inject com.ntech.cabosse.shared.tenant.TenantContext tenantContext;
    @Inject org.eclipse.microprofile.jwt.JsonWebToken jwt;

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }

    private void audit(PurchaseOrderEntity bc, AuditEventType type, CloudFileEntity file,
                       String description) {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("fileName", file != null ? file.originalFileName : null);
        payload.put("mimeType", file != null ? file.mimeType : null);
        payload.put("sizeBytes", file != null ? file.sizeBytes : null);
        audit.event(type)
                .actorEmail(actor())
                .target("purchase_order", bc.id.toString(), bc.ref)
                .tenant(tenantContext.tenantId(), null)
                .description(description)
                .payload(payload)
                .record();
    }

    /** Les méta d'un fichier, ou null : l'audit n'a pas à échouer pour un binaire disparu. */
    private CloudFileEntity metaOrNull(UUID fileId) {
        try { return uploads.findById(SCOPE, fileId); } catch (RuntimeException e) { return null; }
    }

    public void attach(UUID orderId, byte[] bytes, String mimeType, String originalName) {
        PurchaseOrderEntity bc = orders.findById(orderId).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.ach-bc-not-found", orderId))
        );
        if (bc.attachmentFileId != null) {
            uploads.archive(SCOPE, bc.attachmentFileId);
        }
        CloudFileEntity file = uploads.upload(
                SCOPE,
                bytes, mimeType,
                originalName != null ? originalName : "facture-" + bc.ref,
                UPLOAD_TYPE, bc.id, OWNER_TYPE
        );
        bc.attachmentFileId = file.id;
        bc.updatedAt = Instant.now();
        orders.replace(bc);
        audit(bc, AuditEventType.ATTACHMENT_ADDED, file,
                "Facture « " + file.originalFileName + " » jointe au bon de commande " + bc.ref);
    }

    public void detach(UUID orderId) {
        PurchaseOrderEntity bc = orders.findById(orderId).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.ach-bc-not-found", orderId))
        );
        CloudFileEntity previous = null;
        if (bc.attachmentFileId != null) {
            previous = metaOrNull(bc.attachmentFileId);
            uploads.archive(SCOPE, bc.attachmentFileId);
            bc.attachmentFileId = null;
        }
        bc.updatedAt = Instant.now();
        orders.replace(bc);
        if (previous != null) {
            audit(bc, AuditEventType.ATTACHMENT_REMOVED, previous,
                    "Facture « " + previous.originalFileName + " » retirée du bon de commande " + bc.ref);
        }
    }

    public AttachmentStream open(UUID orderId) {
        PurchaseOrderEntity bc = orders.findById(orderId).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.ach-bc-not-found", orderId))
        );
        if (bc.attachmentFileId == null) {
            throw new NotFoundException(Messages.msg("m.ach-bc-no-invoice", orderId));
        }
        CloudFileEntity file = uploads.findById(SCOPE, bc.attachmentFileId);
        InputStream content = uploads.open(SCOPE, file.id);
        return new AttachmentStream(content, file.mimeType, file.sizeBytes, file.originalFileName);
    }

    public record AttachmentStream(InputStream content, String mimeType,
                                   long sizeBytes, String fileName) {}
}
