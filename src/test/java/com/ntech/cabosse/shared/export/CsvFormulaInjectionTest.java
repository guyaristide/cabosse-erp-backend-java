package com.ntech.cabosse.shared.export;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Un export ne fait pas exécuter de formule à celui qui l'ouvre.
 *
 * <p>Les cellules d'un export CSV viennent de champs libres : un nom de
 * membre, une note, un libellé. Un utilisateur qui nomme un membre
 * {@code =cmd|'/c calc'!A1} fait exécuter la commande sur le poste du
 * comptable qui ouvre l'export dans un tableur (audit du 17/10/2026).
 * L'apostrophe en tête est ce que les tableurs lisent comme « texte,
 * tel quel » ; elle ne doit pas toucher aux nombres négatifs, que les
 * imports relisent comme des nombres.</p>
 */
class CsvFormulaInjectionTest {

    @Test
    void a_cell_starting_like_a_formula_is_defused() {
        assertThat(Exporters.csvCell("=cmd|'/c calc'!A1")).isEqualTo("'=cmd|'/c calc'!A1");
        assertThat(Exporters.csvCell("+1+1")).isEqualTo("'+1+1");
        assertThat(Exporters.csvCell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(Exporters.csvCell("\t=1")).isEqualTo("'\t=1");
        assertThat(Exporters.csvCell("-1+1")).isEqualTo("'-1+1");
    }

    @Test
    void a_hyperlink_formula_keeps_its_quoting_on_top_of_the_defusing() {
        String cell = Exporters.csvCell("=HYPERLINK(\"https://x\",\"Cliquez\")");
        assertThat(cell).startsWith("\"'=HYPERLINK(").endsWith("\"");
    }

    @Test
    void negative_amounts_and_percentages_stay_numbers() {
        assertThat(Exporters.csvCell("-1234")).isEqualTo("-1234");
        assertThat(Exporters.csvCell("-1 875 000")).isEqualTo("-1 875 000");
        // La virgule vaut des guillemets, comme pour toute cellule ; pas d'apostrophe.
        assertThat(Exporters.csvCell("-3,2 %")).isEqualTo("\"-3,2 %\"");
        assertThat(Exporters.csvCell("-0.5")).isEqualTo("-0.5");
    }

    @Test
    void ordinary_text_is_untouched() {
        assertThat(Exporters.csvCell("Kouassi Aya")).isEqualTo("Kouassi Aya");
        assertThat(Exporters.csvCell("")).isEqualTo("");
        assertThat(Exporters.csvCell(null)).isEqualTo("");
    }
}
