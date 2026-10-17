package com.ntech.cabosse.shared.storage;

import com.ntech.cabosse.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Le type d'un fichier se lit dans le fichier, pas dans ce que le
 * client en dit.
 *
 * <p>La validation ne comparait que la chaîne annoncée : n'importe quel
 * binaire passait en s'annonçant document, et le disque reflétait le
 * mensonge, l'extension de stockage étant dérivée du même type (audit
 * du 17/10/2026).</p>
 */
class FileSignatureTest {

    private static byte[] pdf() {
        return "%PDF-1.4 ...".getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] png() {
        return new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0 };
    }

    @Test
    void a_binary_declared_as_pdf_is_refused() {
        assertThatThrownBy(() -> FileUploadLimits.enforce("member.document", png(), "application/pdf"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> FileUploadLimits.enforce("member.document",
                "<html><script>1</script></html>".getBytes(StandardCharsets.UTF_8), "application/pdf"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void a_real_pdf_or_image_passes() {
        FileUploadLimits.enforce("member.document", pdf(), "application/pdf");
        FileUploadLimits.enforce("product.image", png(), "image/png");
        FileUploadLimits.enforce("product.image",
                new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0 }, "image/jpeg");
        FileUploadLimits.enforce("product.image",
                "RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.US_ASCII), "image/webp");
    }

    @Test
    void a_riff_container_that_is_not_webp_is_refused() {
        assertThat(FileUploadLimits.matchesSignature("image/webp",
                "RIFF\0\0\0\0WAVEfmt ".getBytes(StandardCharsets.US_ASCII))).isFalse();
    }

    @Test
    void the_logo_no_longer_accepts_a_vector_file() {
        assertThatThrownBy(() -> FileUploadLimits.enforce("tenant.logo",
                "<svg xmlns='http://www.w3.org/2000/svg'><script>1</script></svg>"
                        .getBytes(StandardCharsets.UTF_8), "image/svg+xml"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void a_type_without_known_signature_is_only_checked_by_the_allow_list() {
        FileUploadLimits.enforce("export.report", "date,label\n".getBytes(StandardCharsets.UTF_8), "text/csv");
    }
}
