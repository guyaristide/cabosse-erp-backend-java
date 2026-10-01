package com.ntech.cabosse.tenant;

import com.ntech.cabosse.tenant.service.TenantCollections;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aucune collection n'échappe au classement.
 *
 * <p>La remise à plat partielle efface ce qui n'appartient à aucune des
 * familles conservées. Une collection oubliée dans la carte serait donc
 * effacée en silence, y compris quand l'utilisateur a coché la case qui
 * devait la garder ; et rien ne le signalerait, ni à la construction ni à
 * l'exécution.</p>
 *
 * <p>Ce test lit les sources et exige qu'une collection déclarée soit
 * classée quelque part, fût-ce en exploitation. Ajouter une collection
 * oblige ainsi à dire ce qu'une remise à plat doit en faire.</p>
 */
class TenantCollectionsCoverageTest {

    /** Le motif canonique d'un accès tenant : une constante par repository. */
    private static final Pattern DECLARATION =
            Pattern.compile("String\\s+COLLECTION\\s*=\\s*\"([a-z_0-9]+)\"");

    private Set<String> declaredCollections() throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = DECLARATION.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    found.add(m.group(1));
                }
            }
        }
        return found;
    }

    @Test
    void toute_collection_declaree_est_classee() throws IOException {
        Set<String> declared = declaredCollections();
        // Le test se garde lui-même : si le motif de déclaration change,
        // il trouverait zéro collection et passerait sans rien vérifier.
        assertThat(declared).hasSizeGreaterThan(50);

        Set<String> unclassified = new TreeSet<>(declared);
        unclassified.removeAll(TenantCollections.classified());
        unclassified.removeAll(TenantCollections.TECHNICAL);
        unclassified.remove(TenantCollections.ROLES);

        assertThat(unclassified)
                .as("collections sans famille : une remise à plat partielle les effacerait "
                        + "sans le dire, même quand la case qui devait les garder est cochée")
                .isEmpty();
    }

    @Test
    void la_carte_ne_classe_rien_qui_n_existe_plus() throws IOException {
        Set<String> declared = declaredCollections();

        Set<String> orphans = new TreeSet<>(TenantCollections.classified());
        orphans.removeAll(declared);

        // Une collection renommée et laissée dans la carte donne
        // l'illusion d'être traitée alors qu'elle ne l'est plus.
        assertThat(orphans).as("collections classées que plus aucun code ne déclare").isEmpty();
    }
}
