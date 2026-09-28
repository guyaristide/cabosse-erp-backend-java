package com.ntech.cabosse.shared.startup;

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
 * La liste des origines autorisées (28/09/2026).
 *
 * <p>Toutes les API ont répondu 403 après un déploiement. La variable qui
 * porte le domaine était définie vide : tant qu'elle était seule dans la
 * liste, la liste était vide, et une liste vide ne restreint rien, ce qui
 * était déjà le défaut de sécurité relevé la veille. Y ajouter les postes
 * de développement l'a rendue non vide, donc appliquée, et l'adresse du
 * site n'y figurait nulle part.</p>
 *
 * <p>Les deux états sont mauvais et aucun ne se voyait. Figer le domaine
 * dans le fichier les écarterait tous les deux, mais ferait payer chaque
 * nouvelle installation : le domaine reste donc une donnée de
 * déploiement, et c'est un contrôle au démarrage qui rend l'oubli
 * impossible à rater.</p>
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
    void le_domaine_du_site_reste_une_donnee_de_deploiement(String profile) throws IOException {
        // Le figer ici ferait payer chaque nouvelle installation : les
        // domaines changent d'un déploiement à l'autre, la configuration
        // du dépôt ne les connaît pas.
        assertThat(originsOf(profile))
                .as("l'adresse du site vient du déploiement")
                .contains("${CORS_ORIGINS");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "qa"})
    void les_postes_locaux_restent_admis(String profile) throws IOException {
        // Demandé explicitement : continuer à développer contre l'API en
        // ligne. Eux sont une constante de l'outillage, pas une donnée
        // d'installation, d'où leur place dans le fichier.
        assertThat(originsOf(profile)).contains("localhost").contains("127");
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
    void une_liste_sans_site_est_refusee() {
        // L'état exact du 28/09 : la variable vide ne laisse que les
        // postes locaux, et plus personne ne peut appeler l'API. Le
        // démarrage s'arrête plutôt que de servir une application que
        // son propre site ne peut pas joindre.
        assertThat(CorsOriginsCheck.designatesASite(
                List.of("/https?://localhost(:\\d+)?/", "/https?://127\\.0\\.0\\.1(:\\d+)?/")))
                .isFalse();
        assertThat(CorsOriginsCheck.designatesASite(List.of(""))).isFalse();
        assertThat(CorsOriginsCheck.designatesASite(List.of())).isFalse();
    }

    @Test
    void une_liste_qui_nomme_un_site_passe() {
        // Un domaine quelconque suffit : le contrôle ne connaît aucune
        // adresse en particulier, sans quoi il faudrait le modifier à
        // chaque installation, ce qu'on cherche précisément à éviter.
        assertThat(CorsOriginsCheck.designatesASite(
                List.of("https://exemple.com", "/https?://localhost(:\\d+)?/"))).isTrue();
        assertThat(CorsOriginsCheck.designatesASite(
                List.of("https://cabosse.poc-demo.com"))).isTrue();
    }
}
