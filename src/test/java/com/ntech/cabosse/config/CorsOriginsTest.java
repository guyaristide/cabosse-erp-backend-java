package com.ntech.cabosse.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La liste des origines autorisées, telle qu'elle est écrite (28/09/2026).
 *
 * <p>Toutes les API ont répondu 403 après un déploiement. La liste ne
 * contenait qu'une variable posée vide : vide, elle ne restreignait rien,
 * ce qui était déjà le défaut de sécurité relevé la veille. Y ajouter les
 * postes de développement l'a rendue non vide, donc appliquée, et
 * l'adresse du site n'y figurait nulle part.</p>
 *
 * <p>Le contrôle porte sur le fichier plutôt que sur un serveur démarré :
 * la panne était dans le texte de la ligne, et un serveur de test ne lit
 * pas ces profils. Trois pièges tenus ici, chacun ayant sa forme propre :
 * une liste qui se vide, une origine tirée d'une variable qui peut
 * manquer, et un antislash simple, que la lecture d'une liste consomme
 * avant que l'expression n'atteigne son moteur.</p>
 */
class CorsOriginsTest {

    private static final Pattern ORIGINS_LINE =
            Pattern.compile("^\\s*origins:\\s*(.+)$", Pattern.MULTILINE);

    private static String originsOf(String profile) throws IOException {
        Path p = Path.of("src/main/resources/application-" + profile + ".yml");
        var m = ORIGINS_LINE.matcher(Files.readString(p));
        assertThat(m.find()).as("le profil %s déclare une liste d'origines", profile).isTrue();
        return m.group(1).trim();
    }

    private static List<String> entriesOf(String profile) throws IOException {
        return List.of(originsOf(profile).split(","));
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "qa"})
    void l_adresse_du_site_figure_dans_la_liste(String profile) throws IOException {
        // Sans elle, l'application entière répond 403 : c'est la panne
        // du 28/09, et rien dans la configuration ne la signalait.
        assertThat(entriesOf(profile))
                .as("le domaine servi au public est autorisé")
                .contains("https://cabosse.poc-demo.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "qa"})
    void aucune_origine_ne_depend_d_une_variable(String profile) throws IOException {
        // Une variable absente laisse une entrée vide, une variable vide
        // écrase jusqu'au défaut qu'on lui donnerait : dans les deux cas
        // la liste ment sur ce qu'elle autorise. Pour ajouter une origine
        // au déploiement, QUARKUS_HTTP_CORS_ORIGINS remplace la ligne
        // entière, et ne peut pas la vider par accident.
        assertThat(originsOf(profile))
                .as("la liste ne se lit pas dans l'environnement")
                .doesNotContain("${");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "qa"})
    void aucune_entree_n_est_vide(String profile) throws IOException {
        // Une liste entièrement vide n'est pas une liste stricte : elle
        // laisse passer toutes les origines, y compris une origine
        // pirate. C'est le défaut relevé le 27/09.
        assertThat(entriesOf(profile)).isNotEmpty().allSatisfy(
                e -> assertThat(e.trim()).as("une entrée vide n'autorise rien de lisible")
                        .isNotEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "qa"})
    void les_expressions_doublent_leurs_antislashs(String profile) throws IOException {
        for (String e : entriesOf(profile)) {
            String entry = e.trim();
            if (!(entry.startsWith("/") && entry.endsWith("/"))) continue;
            // La lecture d'une liste consomme l'antislash d'échappement :
            // « \\d » simple arrive au moteur d'expressions sous la forme
            // « d », et le port d'un poste local n'est plus reconnu. Rien
            // ne le signale, l'expression reste valide.
            assertThat(entry.replace("\\\\", ""))
                    .as("l'expression %s garde ses antislashs doublés", entry)
                    .doesNotContain("\\");
        }
    }

    @Test
    void les_postes_locaux_restent_admis() throws IOException {
        // Demandé explicitement : continuer à développer contre l'API en
        // ligne. L'exception tient parce que la session voyage en en-tête
        // et non en cookie, une page locale ne pouvant pas rejouer celle
        // d'autrui.
        assertThat(originsOf("prod")).contains("localhost").contains("127");
    }
}
