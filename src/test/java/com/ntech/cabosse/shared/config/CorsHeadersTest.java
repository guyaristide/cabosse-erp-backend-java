package com.ntech.cabosse.shared.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les en-têtes que le navigateur a le droit d'envoyer.
 *
 * <p>Un en-tête absent de cette liste n'est pas refusé par le serveur :
 * il est refusé par le navigateur, au contrôle préalable, avant que la
 * requête n'atteigne quoi que ce soit. Rien n'apparaît dans les journaux
 * du serveur, et l'écran annonce une panne de réseau alors que le réseau
 * va très bien.</p>
 *
 * <p>Le piège a mordu deux fois. {@code Idempotency-Key} d'abord, sur les
 * flux d'argent. Puis {@code X-Platform-Restore-Secret}, le 23/09/2026 :
 * la remontée du serveur échouait sans laisser de trace, et cela ne s'est
 * vu qu'en ouvrant un vrai navigateur. Aucun test d'API ne peut le voir,
 * puisque le contrôle préalable est un comportement du navigateur.</p>
 *
 * <p>D'où ce contrôle sur les fichiers eux-mêmes. Le danger n'est pas
 * d'oublier partout, c'est d'ajouter en développement et d'oublier en
 * production : tout marche chez soi, et la fonction est morte chez le
 * client.</p>
 */
class CorsHeadersTest {

    /** Les environnements qui servent un navigateur. */
    private static final List<String> ENVIRONMENTS = List.of("dev", "qa", "prod");

    /**
     * Ce que le front envoie et qui n'est pas un en-tête ordinaire.
     *
     * <p>Y ajouter une entrée quand le front se met à poser un nouvel
     * en-tête, dans le même commit : c'est le seul endroit qui relie les
     * deux dépôts.</p>
     */
    private static final Set<String> REQUIRED = new LinkedHashSet<>(List.of(
            "Content-Type",
            "Authorization",
            "Idempotency-Key",
            "X-Platform-Restore-Secret"));

    private static final Pattern HEADERS_LINE =
            Pattern.compile("^\\s*headers:\\s*\"([^\"]*)\"\\s*$", Pattern.MULTILINE);

    private Set<String> declaredIn(String environment) throws IOException {
        Path file = Path.of("src/main/resources/application-" + environment + ".yml");
        Matcher m = HEADERS_LINE.matcher(Files.readString(file, StandardCharsets.UTF_8));
        assertThat(m.find())
                .as("application-%s.yml déclare une liste d'en-têtes", environment)
                .isTrue();
        return new LinkedHashSet<>(Arrays.stream(m.group(1).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    @Test
    void chaque_environnement_autorise_les_en_tetes_que_le_front_envoie() throws IOException {
        for (String environment : ENVIRONMENTS) {
            assertThat(declaredIn(environment))
                    .as("en-têtes autorisés en %s", environment)
                    .containsAll(REQUIRED);
        }
    }

    @Test
    void les_environnements_ne_divergent_pas() throws IOException {
        // Ajouter en développement et oublier en production donne le pire
        // des cas : tout marche chez soi, et la fonction est morte chez
        // le client, sans message qui aide.
        Set<String> reference = declaredIn(ENVIRONMENTS.get(0));
        for (String environment : ENVIRONMENTS) {
            assertThat(declaredIn(environment))
                    .as("application-%s.yml aligné sur application-%s.yml",
                            environment, ENVIRONMENTS.get(0))
                    .isEqualTo(reference);
        }
    }
}
