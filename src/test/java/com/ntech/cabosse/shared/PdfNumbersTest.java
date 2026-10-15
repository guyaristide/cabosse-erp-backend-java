package com.ntech.cabosse.shared;

import com.ntech.cabosse.shared.export.PdfNumbers;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les nombres d'un document imprimé.
 *
 * <p>Le format français de la bibliothèque standard sépare les milliers
 * par une espace fine insécable que l'encodage des polices du PDF ne
 * connaît pas : elle disparaît à l'impression et « 1 806 000 » sort
 * « 1806000 », ce qu'a vu la coopérative sur ses factures
 * (15/10/2026).</p>
 */
class PdfNumbersTest {

    /** L'espace insécable ordinaire, que l'encodage des polices rend. */
    private static final char NBSP = ' ';

    @Test
    void les_milliers_sont_separes_par_une_espace_que_le_pdf_sait_rendre() {
        assertThat(PdfNumbers.money(new BigDecimal("1806000")))
                .isEqualTo("1" + NBSP + "806" + NBSP + "000");
        assertThat(PdfNumbers.quantity(new BigDecimal("10770")))
                .isEqualTo("10" + NBSP + "770");
    }

    @Test
    void aucune_espace_fine_insecable_ne_subsiste() {
        // U+202F est exactement le caractère qui disparaissait.
        assertThat(PdfNumbers.money(new BigDecimal("55886400"))).doesNotContain(" ");
    }

    @Test
    void un_montant_n_a_pas_de_centimes_une_quantite_garde_les_siens() {
        assertThat(PdfNumbers.money(new BigDecimal("1200.49"))).isEqualTo("1" + NBSP + "200");
        assertThat(PdfNumbers.quantity(new BigDecimal("1455.5")))
                .isEqualTo("1" + NBSP + "455,5");
        assertThat(PdfNumbers.quantity(new BigDecimal("852"))).isEqualTo("852");
    }

    @Test
    void une_quantite_absente_se_lit_d_un_tiret() {
        assertThat(PdfNumbers.quantityOrDash(null)).isEqualTo("-");
        assertThat(PdfNumbers.money(null)).isEqualTo("0");
    }
}
