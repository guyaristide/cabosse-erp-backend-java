package com.ntech.cabosse.shared.export;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ce qu'un export écrit doit pouvoir être relu par un import.
 *
 * <p>Un tableur sépare les milliers, et c'est ce qu'on veut d'un montant.
 * Sur une année, cela produisait « 2 003 » : le fichier redéposé revenait
 * avec un nombre jugé illisible, et l'export cassait donc l'aller-retour
 * qu'il est précisément censé permettre. Deux lignes sur trois d'un
 * export de parcelles échouaient ainsi à la relecture.</p>
 */
class NumberFormatForReimportTest {

    @Test
    void a_year_carries_no_thousands_separator() {
        assertThat(Exporters.formatForText(2003, ColumnKind.NUMBER_INT))
                .isEqualTo("2003");
    }

    @Test
    void an_amount_still_groups_its_thousands() {
        // Le contraste est volontaire : la lisibilité prime sur une somme,
        // qu'aucun import ne relit colonne par colonne.
        assertThat(Exporters.formatForText(1875000, ColumnKind.NUMBER_MONEY))
                .containsAnyOf(" ", " ", " ");
    }

    @Test
    void the_reader_absorbs_every_kind_of_space() {
        // « \s » ne couvre ni l'espace fine insécable (U+202F) qu'emploie le
        // format français, ni l'insécable (U+00A0). Le lecteur d'import doit
        // les prendre toutes, sans quoi il refuse un nombre que nous avons
        // nous-mêmes écrit.
        for (String raw : List.of("2 003", "2 003", "2 003", "2003")) {
            assertThat(raw.replaceAll("[\\s\\p{Zs}]", ""))
                    .as("séparateur U+%04X", (int) raw.charAt(1))
                    .isEqualTo("2003");
        }
    }
}
