package com.ntech.cabosse.shared.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les colonnes triables d'une liste.
 *
 * <p>Ce qui se joue ici n'est pas le confort : un nom de colonne venu
 * de la requête qui arriverait tel quel au moteur ferait trier sur un
 * champ sans index, c'est-à-dire parcourir toute la collection, et
 * dirait au passage la forme interne des documents à qui essaie.</p>
 */
class SortFieldsTest {

    private static final SortFields SORTABLE = SortFields.defaultSort("date", false)
            .on("date")
            .on("ref")
            .on("counterparty", "delegateName")
            .build();

    @Test
    void applique_le_tri_par_defaut_sans_demande() {
        var sort = SORTABLE.resolve(PageRequest.of(0, 20));
        assertThat(sort.field()).isEqualTo("date");
        assertThat(sort.ascending()).isFalse();
        assertThat(sort.order()).isEqualTo("desc");
    }

    @Test
    void traduit_le_nom_public_en_champ_du_document() {
        // L'écran parle d'apporteur, le document dit delegateName.
        var sort = SORTABLE.resolve(PageRequest.of(0, 20, "counterparty", "asc"));
        assertThat(sort.name()).isEqualTo("counterparty");
        assertThat(sort.field()).isEqualTo("delegateName");
        assertThat(sort.toDocument().getInteger("delegateName")).isEqualTo(1);
    }

    @Test
    void retombe_sur_le_defaut_quand_la_colonne_n_est_pas_triable() {
        // Ni erreur ni tri sauvage : la liste s'affiche, et l'enveloppe
        // dira le tri réellement appliqué.
        var sort = SORTABLE.resolve(PageRequest.of(0, 20, "notes", "asc"));
        assertThat(sort.name()).isEqualTo("date");
        assertThat(sort.ascending()).isFalse();
    }

    @Test
    void ne_laisse_jamais_passer_un_champ_inconnu_au_moteur() {
        var sort = SORTABLE.resolve(PageRequest.of(0, 20, "{ $where: 1 }", "desc"));
        assertThat(sort.toDocument().keySet()).containsExactly("date");
    }

    @Test
    void refuse_une_declaration_dont_le_defaut_n_est_pas_triable() {
        assertThat(
                org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalStateException.class,
                        () -> SortFields.defaultSort("absent", true).on("date").build()))
                .hasMessageContaining("absent");
    }
}
