package com.ntech.cabosse.accounting;

import com.ntech.cabosse.accounting.service.AccountingService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le compte sur lequel s'impute un tiers (demandé le 29/09/2026).
 *
 * <p>Le compte collectif donne le total dû par l'ensemble des clients ;
 * le compte auxiliaire dit ce que doit celui-ci. Sans compte auxiliaire,
 * le grand livre ne répond pas à « combien nous doit ce client », qui
 * est pourtant la question posée chaque semaine.</p>
 *
 * <p>L'ordre compte : le sien d'abord, son collectif de rattachement
 * ensuite, celui de la structure en dernier. Une fiche sans compte
 * fonctionne donc exactement comme avant, et en ouvrir un ne demande
 * aucune reprise de l'existant.</p>
 */
class PartyAccountTest {

    @Test
    void le_compte_du_tiers_passe_avant_tout() {
        assertThat(AccountingService.partyAccount("411001", "411000", "411000"))
                .isEqualTo("411001");
    }

    @Test
    void sans_compte_propre_le_collectif_de_rattachement_sert() {
        // Tous les clients ne vont pas au même collectif : un client à
        // l'export ne se totalise pas avec la vente de détail.
        assertThat(AccountingService.partyAccount(null, "411200", "411000"))
                .isEqualTo("411200");
        assertThat(AccountingService.partyAccount("  ", "411200", "411000"))
                .isEqualTo("411200");
    }

    @Test
    void sans_rien_la_structure_garde_son_compte() {
        // Le cas de tous les tiers déjà saisis : rien ne change pour eux,
        // et rien ne les oblige à être repris.
        assertThat(AccountingService.partyAccount(null, null, "401000")).isEqualTo("401000");
        assertThat(AccountingService.partyAccount("", "", "401000")).isEqualTo("401000");
    }

    @Test
    void les_espaces_autour_du_numero_ne_font_pas_un_autre_compte() {
        // Saisi au clavier, un numéro traîne souvent une espace. Deux
        // comptes qui ne diffèrent que par elle casseraient le
        // rapprochement entre l'auxiliaire et son collectif.
        assertThat(AccountingService.partyAccount(" 411001 ", null, "411000"))
                .isEqualTo("411001");
    }
}
