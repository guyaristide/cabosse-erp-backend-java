package com.ntech.cabosse.achats.service;

import com.ntech.cabosse.achats.entity.PurchaseOrderEntity;
import com.ntech.cabosse.achats.repository.PurchaseOrderRepository;
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
    }

    public void detach(UUID orderId) {
        PurchaseOrderEntity bc = orders.findById(orderId).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.ach-bc-not-found", orderId))
        );
        if (bc.attachmentFileId != null) {
            uploads.archive(SCOPE, bc.attachmentFileId);
            bc.attachmentFileId = null;
        }
        bc.updatedAt = Instant.now();
        orders.replace(bc);
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
