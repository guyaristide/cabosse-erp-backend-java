package com.ntech.cabosse.diagnostics;

import com.ntech.cabosse.diagnostics.dto.ConsistencyCheckDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Un contrôle dit aussi qui corrige.
 *
 * <p>Compter sans dire quoi faire laisse son lecteur devant un nombre.
 * Ce qui manquait le plus n'était pas la consigne elle-même, qui vit
 * dans le catalogue de l'écran, mais la distinction : la plateforme ne
 * peut pas toucher aux données d'une structure, et une anomalie que
 * l'éditeur doit reprendre par migration ne s'attend pas au même
 * endroit qu'une fiche à créer (17/10/2026).</p>
 */
class DiagnosticRemedyTest {

    @Test
    void un_controle_est_corrige_par_la_structure_par_defaut() {
        var check = new ConsistencyCheckDto("x", 3, List.of("a"));
        assertThat(check.fixedBy()).isEqualTo(ConsistencyCheckDto.Fixer.TENANT);
        assertThat(check.fixPath()).isNull();
    }

    @Test
    void un_controle_porte_l_ecran_ou_se_fait_la_correction() {
        var check = new ConsistencyCheckDto("noteWithoutDelegate", 1, List.of("0918"),
                ConsistencyCheckDto.Fixer.TENANT, "/app/references/suppliers");
        assertThat(check.fixPath()).isEqualTo("/app/references/suppliers");
    }

    @Test
    void une_anomalie_que_seul_l_editeur_reprend_le_dit() {
        // Un reçu sans mouvement de stock ne se répare pas à l'écran :
        // l'annoncer corrigeable sur place ferait chercher pour rien.
        var check = new ConsistencyCheckDto("receiptWithoutMovement", 2, List.of("ACP-1"),
                ConsistencyCheckDto.Fixer.PLATFORM, null);
        assertThat(check.fixedBy()).isEqualTo(ConsistencyCheckDto.Fixer.PLATFORM);
        assertThat(check.fixPath()).isNull();
    }
}
