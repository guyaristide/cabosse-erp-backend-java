package com.ntech.cabosse.shared.export;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La clé identifie une colonne, le libellé l'affiche, la nature la met en
 * forme. Ces trois rôles étaient tenus par le seul libellé.
 *
 * <p>Deux conséquences réparées ici. La sélection de colonnes comparait
 * des libellés français : elle se serait tue dès qu'ils auraient été
 * traduits. Et la mise en forme était devinée en cherchant des mots
 * français dans l'en-tête, ce qui classait « Latitude » en montant, donc
 * arrondi. Une coordonnée sortait à trois décimales, soit une centaine de
 * mètres d'erreur, et le cycle exporter, corriger, réimporter la dégradait
 * à chaque passage.</p>
 */
class ExportColumnKeyTest {

    private record Row(String ref, Double latitude, BigDecimal weight) {}

    private static String csv(List<ExportColumn<Row>> columns, Row row) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Exporters.writeCsv(new ExportDataset<>("Test", columns, List.of(row)), out);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void a_coordinate_keeps_its_precision() {
        String output = csv(List.of(
                ExportColumn.of("latitude", "Latitude", ColumnKind.NUMBER_PRECISE, Row::latitude)
        ), new Row("R1", 5.236830, null));

        assertThat(output).contains("5,23683");
        // Le défaut d'avant : arrondi à trois décimales, une centaine de
        // mètres perdus sur le terrain.
        assertThat(output).doesNotContain("5,237");
    }

    @Test
    void a_declared_quantity_keeps_its_decimals() {
        String output = csv(List.of(
                ExportColumn.of("poids", "Poids net", ColumnKind.NUMBER_QTY, Row::weight)
        ), new Row("R1", null, new BigDecimal("12.45")));

        assertThat(output).contains("12,45");
    }

    @Test
    void an_undeclared_column_without_a_known_word_is_still_rounded() {
        // Comportement historique, conservé tant que la colonne ne déclare
        // rien : c'est précisément ce que la déclaration vient corriger.
        String output = csv(List.of(
                ExportColumn.of("Poids net", Row::weight)
        ), new Row("R1", null, new BigDecimal("12.45")));

        assertThat(output).contains("12");
    }

    @Test
    void a_key_is_derived_from_the_label_when_not_given() {
        assertThat(ExportColumn.slug("Montant HT")).isEqualTo("montant-ht");
        assertThat(ExportColumn.slug("Fèves fraîches (kg)")).isEqualTo("feves-fraiches-kg");
        assertThat(ExportColumn.slug("N° de reçu")).isEqualTo("n-de-recu");
        // Une clé dérivée reste stable face à une traduction du libellé,
        // puisqu'elle est figée à la déclaration de la colonne.
        assertThat(ExportColumn.of("Statut", Row::ref).key()).isEqualTo("statut");
    }

    @Test
    void a_declared_column_carries_its_three_roles() {
        ExportColumn<Row> column =
                ExportColumn.of("latitude", "Latitude", ColumnKind.NUMBER_PRECISE, Row::latitude);

        assertThat(column.key()).isEqualTo("latitude");
        assertThat(column.header()).isEqualTo("Latitude");
        assertThat(column.kind()).isEqualTo(ColumnKind.NUMBER_PRECISE);
    }
}
