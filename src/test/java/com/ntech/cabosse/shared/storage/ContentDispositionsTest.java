package com.ntech.cabosse.shared.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Un nom de fichier choisi par le client ne casse pas l'en-tête qui le
 * porte.
 */
class ContentDispositionsTest {

    @Test
    void a_quote_or_a_line_break_cannot_leave_the_quoted_form() {
        String header = ContentDispositions.inline("fac\"ture\r\nX-Injected: 1.pdf", "piece");
        String quoted = header.substring(header.indexOf('"') + 1, header.indexOf('"', header.indexOf('"') + 1));
        assertThat(quoted).doesNotContain("\"").doesNotContain("\r").doesNotContain("\n");
        assertThat(header).doesNotContain("\r").doesNotContain("\n");
    }

    @Test
    void an_accented_name_is_kept_in_the_encoded_form() {
        String header = ContentDispositions.attachment("reçu n°12.pdf", "piece");
        assertThat(header).startsWith("attachment; filename=\"re_u n_12.pdf\"");
        assertThat(header).contains("filename*=UTF-8''re%C3%A7u%20n%C2%B012.pdf");
    }

    @Test
    void a_missing_name_takes_the_fallback() {
        assertThat(ContentDispositions.inline("  ", "piece")).startsWith("inline; filename=\"piece\"");
        assertThat(ContentDispositions.inline(null, "facture")).startsWith("inline; filename=\"facture\"");
    }
}
