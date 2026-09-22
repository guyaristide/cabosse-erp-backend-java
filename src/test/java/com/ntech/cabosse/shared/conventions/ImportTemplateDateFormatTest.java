package com.ntech.cabosse.shared.conventions;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Un seul format de date dans les modèles d'import.
 *
 * <p>Six modèles proposaient une date en {@code jj/mm/aaaa}, deux la
 * proposaient en {@code aaaa-mm-jj}. Les deux se relisent, donc rien ne
 * tombait en erreur ; mais un exemple n'est pas une valeur, c'est une
 * consigne. L'utilisateur retient le format du dernier modèle ouvert et
 * se trompe au module suivant : « imagine qu'on doit à chaque fois faire
 * attention au format d'un module à l'autre » (relevé le 22/09/2026 sur
 * le module ventes).</p>
 *
 * <p>Le format retenu est celui que l'utilisateur écrit partout ailleurs
 * dans l'application. La relecture, elle, reste tolérante : ce cliquet
 * porte sur ce qu'on propose, pas sur ce qu'on accepte.</p>
 */
class ImportTemplateDateFormatTest {

    /** Une date ISO citée entre guillemets dans un modèle. */
    private static final Pattern ISO = Pattern.compile("\"\\d{4}-\\d{2}-\\d{2}\"");

    @Test
    void aucun_modele_ne_propose_une_date_en_annee_mois_jour() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path p : files.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith("ImportTemplate.java")
                            || f.getFileName().toString().endsWith("ImportTemplates.java"))
                    .toList()) {
                String source = Files.readString(p);
                Matcher m = ISO.matcher(source);
                while (m.find()) {
                    offenders.add(p.getFileName() + " : " + m.group());
                }
            }
        }
        assertThat(offenders)
                .as("un modèle d'import propose une date en aaaa-mm-jj ; "
                        + "écrire l'exemple en jj/mm/aaaa, comme partout ailleurs")
                .isEmpty();
    }
}
