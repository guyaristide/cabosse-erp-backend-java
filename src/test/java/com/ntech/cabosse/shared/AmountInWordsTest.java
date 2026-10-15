package com.ntech.cabosse.shared;

import com.ntech.cabosse.shared.export.AmountInWords;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le montant en toutes lettres, sous le montant en chiffres.
 *
 * <p>Une pièce que l'on signe le porte : c'est ce qui empêche d'ajouter
 * un zéro après coup (demandé le 14/10/2026).</p>
 */
class AmountInWordsTest {

    private static String fr(long n) {
        return AmountInWords.of(BigDecimal.valueOf(n), Locale.FRANCE);
    }

    @Test
    void les_montants_courants_d_une_cooperative() {
        assertThat(fr(10_000_000)).isEqualTo("Dix millions");
        assertThat(fr(4_747_300)).isEqualTo("Quatre millions sept cent quarante-sept mille trois cents");
        assertThat(fr(21_335_000)).isEqualTo("Vingt et un millions trois cent trente-cinq mille");
        assertThat(fr(1_200)).isEqualTo("Mille deux cents");
    }

    @Test
    void les_pieges_du_francais() {
        assertThat(fr(80)).isEqualTo("Quatre-vingts");
        assertThat(fr(81)).isEqualTo("Quatre-vingt-un");
        assertThat(fr(71)).isEqualTo("Soixante et onze");
        assertThat(fr(97)).isEqualTo("Quatre-vingt-dix-sept");
        assertThat(fr(100)).isEqualTo("Cent");
        assertThat(fr(200)).isEqualTo("Deux cents");
        assertThat(fr(201)).isEqualTo("Deux cent un");
        assertThat(fr(1_000)).isEqualTo("Mille");
        assertThat(fr(0)).isEqualTo("Zéro");
    }

    @Test
    void l_anglais_suit_la_langue_demandee() {
        assertThat(AmountInWords.of(BigDecimal.valueOf(10_000_000), Locale.ENGLISH))
                .isEqualTo("Ten million");
        assertThat(AmountInWords.of(BigDecimal.valueOf(1_205), Locale.ENGLISH))
                .isEqualTo("One thousand two hundred and five");
    }
}
