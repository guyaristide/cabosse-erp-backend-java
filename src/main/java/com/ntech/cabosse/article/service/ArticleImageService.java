package com.ntech.cabosse.article.service;

import com.ntech.cabosse.article.entity.ArticleEntity;
import com.ntech.cabosse.article.repository.ArticleRepository;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.storage.CloudFileEntity;
import com.ntech.cabosse.shared.storage.CloudFileScope;
import com.ntech.cabosse.shared.storage.FileUploadService;
import com.ntech.cabosse.shared.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;

/**
 * Gestion de l'image d'un article.
 *
 * <p>Le binaire vit dans {@code <tenant_db>.cloud_files} (scope
 * {@link CloudFileScope#TENANT}). L'article ne porte que la référence
 * {@code imageFileId} — les méta (mime, taille) sont lues à la volée
 * depuis {@code CloudFileEntity} pour ne pas se créer d'incohérences.</p>
 *
 * <p>Type d'upload : {@code "product.image"} (cf. {@code FileUploadLimits.RULES}
 * — 2 MB max, JPEG / PNG / WebP).</p>
 */
@ApplicationScoped
public class ArticleImageService {

    private static final String UPLOAD_TYPE = "product.image";
    private static final String OWNER_TYPE = "article";
    private static final CloudFileScope SCOPE = CloudFileScope.TENANT;

    @Inject ArticleRepository articles;
    @Inject FileUploadService uploads;
    @Inject TenantContext tenantContext;

    /**
     * Attache ou remplace l'image d'un article. Si une image existait, elle
     * est archivée (soft delete) — le job de nettoyage récupèrera le binaire
     * orphelin.
     */
    public void attachImage(UUID articleId, byte[] bytes, String mimeType) {
        ArticleEntity article = articles.findById(articleId).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.art-not-found", articleId))
        );

        if (article.imageFileId != null) {
            uploads.archive(SCOPE, article.imageFileId);
        }

        CloudFileEntity file = uploads.upload(
                SCOPE,
                bytes, mimeType,
                /* originalFileName */ "article-" + article.code + "." + extOf(mimeType),
                UPLOAD_TYPE, article.id, OWNER_TYPE
        );

        article.imageFileId = file.id;
        article.updatedAt = Instant.now();
        articles.replace(article);
    }

    /** Retire l'image d'un article (no-op s'il n'y en a pas). */
    public void detachImage(UUID articleId) {
        ArticleEntity article = articles.findById(articleId).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.art-not-found", articleId))
        );
        if (article.imageFileId != null) {
            uploads.archive(SCOPE, article.imageFileId);
            article.imageFileId = null;
        }
        article.updatedAt = Instant.now();
        articles.replace(article);
    }

    /** Ouvre un stream sur le binaire de l'image, avec ses méta. */
    public ImageStream openImage(UUID articleId) {
        ArticleEntity article = articles.findById(articleId).orElseThrow(
                () -> new NotFoundException(Messages.msg("m.art-not-found", articleId))
        );
        if (article.imageFileId == null) {
            throw new NotFoundException(Messages.msg("m.art-no-image", articleId));
        }
        CloudFileEntity file = uploads.findById(SCOPE, article.imageFileId);
        InputStream content = uploads.open(SCOPE, file.id);
        return new ImageStream(content, file.mimeType, file.sizeBytes);
    }

    /** Tuple stream + méta utilisé par le controller pour servir le binaire. */
    public record ImageStream(InputStream content, String mimeType, long sizeBytes) {}

    private static String extOf(String mimeType) {
        if (mimeType == null) return "bin";
        return switch (mimeType.toLowerCase()) {
            case "image/png" -> "png";
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/webp" -> "webp";
            default -> "bin";
        };
    }
}
