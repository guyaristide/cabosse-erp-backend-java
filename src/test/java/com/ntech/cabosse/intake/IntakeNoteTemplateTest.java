package com.ntech.cabosse.intake;

import com.ntech.cabosse.intake.controller.IntakeNoteImportTemplate;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le modèle proposé au téléchargement, figé.
 *
 * <p>Le 15/09/2026, le carnet de la coopérative a révélé que la lecture
 * tombait sur « Code fournisseur » au lieu de « Fournisseur », les deux
 * se ressemblant et le code venant en premier. Le modèle et sa lecture
 * doivent donc rester d'accord, et rien ne le garantissait : on pouvait
 * renommer une colonne du modèle sans que le mapping en sache rien.</p>
 *
 * <p>Ce test ne juge pas du bon ordre, il le fixe. Changer une colonne
 * fera tomber cette liste, ce qui est exactement le rappel attendu :
 * la lecture côté application doit être reprise dans le même geste.</p>
 */
@QuarkusTest
class IntakeNoteTemplateTest {

    @Inject IntakeNoteImportTemplate template;

    @Test
    void the_template_keeps_the_columns_the_cooperative_book_carries() {
        List<String> headers = template.dataset().columns().stream()
                .map(c -> c.header())
                .toList();

        assertThat(headers).containsExactly(
                "Campagne", "Type produit", "Date", "Mouvement", "N° BR", "Camion",
                "Code fournisseur", "Fournisseur", "N°", "Poids brut", "Nb sacs", "Poids net");
    }

    /**
     * Deux pièges de lecture tiennent à ces intitulés, et tous deux se
     * sont produits : « Code fournisseur » contient « fournisseur », et
     * « Nb sacs » contiendrait « nbr » s'il s'écrivait « Nbre de sacs »,
     * comme le numéro de bordereau.
     */
    @Test
    void the_template_ships_an_example_line_that_exercises_the_traps() {
        var rows = template.dataset().rows();
        assertThat(rows).hasSize(1);
        var row = rows.get(0);
        // Le code vide et le nom rempli : exactement le cas qui faisait
        // partir un bordereau sans aucun fournisseur.
        assertThat(row.supplierCode()).isEmpty();
        assertThat(row.supplierName()).isNotBlank();
    }
}
