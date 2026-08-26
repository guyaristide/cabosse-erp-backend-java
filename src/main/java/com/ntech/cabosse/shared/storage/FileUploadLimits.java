package com.ntech.cabosse.shared.storage;

import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.i18n.Messages;

import java.util.Map;
import java.util.Set;

/**
 * Registre des règles par type de fichier (cf. {@code file-storage.md} §9).
 *
 * <p>Ajouter un nouveau type = ajouter une entrée à {@link #RULES}. Tout
 * type inconnu est rejeté en {@code 422 Unprocessable Entity}.</p>
 */
public final class FileUploadLimits {

    private FileUploadLimits() {}

    /** Règle de validation pour un type d'usage donné. */
    public record TypeRule(long maxBytes, Set<String> allowedMimes) {}

    private static final Set<String> COMMON_IMAGE_MIMES = Set.of(
            "image/png", "image/jpeg", "image/jpg", "image/webp"
    );

    private static final Set<String> LOGO_MIMES = Set.of(
            "image/png", "image/jpeg", "image/jpg", "image/webp", "image/svg+xml"
    );

    public static final Map<String, TypeRule> RULES = Map.of(
            "tenant.logo",
            new TypeRule(1_000_000L, LOGO_MIMES),

            "product.image",
            new TypeRule(2_000_000L, COMMON_IMAGE_MIMES),

            "purchase_order.attachment",
            new TypeRule(10_000_000L, Set.of("application/pdf", "image/png", "image/jpeg")),

            "member.document",
            new TypeRule(10_000_000L, Set.of("application/pdf", "image/png", "image/jpeg")),

            "od.document",
            new TypeRule(10_000_000L, Set.of("application/pdf", "image/png", "image/jpeg")),

            "fiscal_year.document",
            new TypeRule(10_000_000L, Set.of("application/pdf", "image/png", "image/jpeg")),

            "financing.attachment",
            new TypeRule(10_000_000L, Set.of("application/pdf", "image/png", "image/jpeg")),

            "user.avatar",
            new TypeRule(500_000L, COMMON_IMAGE_MIMES),

            "export.report",
            new TypeRule(50_000_000L, Set.of(
                    "application/pdf",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "text/csv"
            ))
    );

    /**
     * Lève {@link BusinessException} si le type est inconnu, ou si la taille
     * / le mime ne respectent pas la règle.
     */
    public static void enforce(String type, long sizeBytes, String mimeType) {
        TypeRule rule = RULES.get(type);
        if (rule == null) {
            throw new BusinessException(Messages.msg("m.shr-file-type-unknown", type));
        }
        if (sizeBytes <= 0) {
            throw new BusinessException(Messages.msg("m.shr-file-empty"));
        }
        if (sizeBytes > rule.maxBytes()) {
            throw new BusinessException(
                    Messages.msg("m.shr-file-too-large",
                            String.valueOf(sizeBytes), String.valueOf(rule.maxBytes()), type));
        }
        if (mimeType == null || !rule.allowedMimes().contains(mimeType.toLowerCase())) {
            throw new BusinessException(
                    Messages.msg("m.shr-mime-not-allowed", mimeType, type, rule.allowedMimes()));
        }
    }
}
