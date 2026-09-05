package com.ntech.cabosse.shared.i18n;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le libellé d'affichage d'une devise.
 *
 * <p>Personne n'écrit « 1 500 000 XOF » sur un talon de chèque : les deux
 * francs CFA s'affichent FCFA. Les autres devises gardent leur code tant
 * que le catalogue (CE-60) n'a pas fourni mieux.</p>
 */
class CurrencyLabelsTest {

    @Test
    void the_two_cfa_francs_read_fcfa() {
        assertThat(CurrencyLabels.display("XOF")).isEqualTo("FCFA");
        assertThat(CurrencyLabels.display("XAF")).isEqualTo("FCFA");
        assertThat(CurrencyLabels.display("xof")).isEqualTo("FCFA");
    }

    @Test
    void any_other_currency_keeps_its_code() {
        assertThat(CurrencyLabels.display("GHS")).isEqualTo("GHS");
        assertThat(CurrencyLabels.display("eur")).isEqualTo("EUR");
    }

    @Test
    void the_absence_of_a_currency_falls_back_on_the_default() {
        // Hors requête, ou tenant sans préférence : le défaut de la
        // plateforme, pas une chaîne vide qui ferait des en-têtes « () ».
        assertThat(CurrencyLabels.display(null)).isEqualTo("FCFA");
        assertThat(CurrencyLabels.display("  ")).isEqualTo("FCFA");
    }
}
