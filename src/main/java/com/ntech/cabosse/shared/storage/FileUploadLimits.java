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

    /**
     * Le format vectoriel n'en fait plus partie : un fichier SVG peut
     * porter du script, et servi sous son vrai type depuis l'origine de
     * l'API, ouvert directement, il l'exécuterait (audit du 17/10/2026).
     * Les logos déjà déposés restent servis, sous une politique de
     * contenu qui interdit tout script.
     */
    private static final Set<String> LOGO_MIMES = COMMON_IMAGE_MIMES;

    /**
     * Les premiers octets qu'un fichier de ce type porte réellement.
     *
     * <p>Le type annoncé par le client n'est qu'une chaîne : n'importe
     * quel binaire passait en se disant document. Un type sans signature
     * connue ici n'est vérifié que par la liste des types autorisés.</p>
     */
    private static final Map<String, java.util.List<byte[]>> SIGNATURES = Map.of(
            "application/pdf", java.util.List.of("%PDF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
            "image/png", java.util.List.of(new byte[] {
                    (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A }),
            "image/jpeg", java.util.List.of(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF }),
            "image/jpg", java.util.List.of(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF }),
            "image/webp", java.util.List.of("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)),
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            java.util.List.of(new byte[] { 'P', 'K', 0x03, 0x04 })
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
            )),

            // Le fichier déposé à un import, gardé tel quel (DEC-52) : CSV ou
            // Excel, tels que les navigateurs les nomment, parfois sans type.
            "import.source",
            new TypeRule(20_000_000L, Set.of(
                    "text/csv",
                    "text/plain",
                    "application/csv",
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/octet-stream"
            ))
    );

    /**
     * Lève {@link BusinessException} si le type est inconnu, si la taille
     * ou le mime ne respectent pas la règle, ou si le contenu ne commence
     * pas par ce qu'un fichier du type annoncé commence toujours.
     */
    public static void enforce(String type, byte[] content, String mimeType) {
        enforce(type, content != null ? content.length : 0, mimeType);
        if (!matchesSignature(mimeType, content)) {
            throw new BusinessException(Messages.msg("m.shr-file-signature-mismatch", mimeType));
        }
    }

    /** Vrai si le contenu commence comme un fichier de ce type, ou si le type n'a pas de signature connue. */
    static boolean matchesSignature(String mimeType, byte[] content) {
        java.util.List<byte[]> expected = mimeType == null ? null : SIGNATURES.get(mimeType.toLowerCase());
        if (expected == null) return true;
        if (content == null) return false;
        for (byte[] magic : expected) {
            if (content.length >= magic.length
                    && java.util.Arrays.equals(content, 0, magic.length, magic, 0, magic.length)) {
                if (!"image/webp".equalsIgnoreCase(mimeType)) return true;
                // RIFF n'est qu'un conteneur : WEBP se lit à l'octet 8.
                return content.length >= 12 && content[8] == 'W' && content[9] == 'E'
                        && content[10] == 'B' && content[11] == 'P';
            }
        }
        return false;
    }

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
