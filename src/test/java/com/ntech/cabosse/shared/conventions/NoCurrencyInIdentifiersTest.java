package com.ntech.cabosse.shared.conventions;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plus aucun identifiant nommé d'après une devise.
 *
 * <p>Vague 3 du chantier devise (04/09/2026) : 145 identifiants
 * {@code *Fcfa}, champs persistés et contrats d'API compris, ont perdu
 * leur suffixe, et la migration {@code M084} a renommé les clés déjà
 * écrites en base. Ce cliquet empêche le suffixe de renaître : la devise
 * est une préférence du tenant, elle ne se grave ni dans un nom de champ
 * ni dans une clé de catalogue.</p>
 *
 * <p>« FCFA » tout en capitales reste licite : c'est une valeur
 * d'affichage ({@code CurrencyLabels}) ou une citation en commentaire,
 * pas un nom. Le motif interdit est la casse mixte {@code Fcfa}, qui ne
 * peut venir que d'un identifiant camelCase.</p>
 */
class NoCurrencyInIdentifiersTest {

    @Test
    void no_identifier_is_named_after_a_currency() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path root : List.of(Path.of("src/main"), Path.of("src/test"))) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(f -> {
                    String name = f.getFileName().toString();
                    return name.endsWith(".java") || name.endsWith(".properties");
                }).toList()) {
                    // La migration qui a retiré le suffixe doit pouvoir
                    // nommer ce qu'elle retire ; ce cliquet aussi.
                    if (file.endsWith(Path.of("shared/conventions/NoCurrencyInIdentifiersTest.java"))
                            || file.endsWith(Path.of("migrations/M084_DropFcfaFromFieldNames.java"))) {
                        continue;
                    }
                    List<String> lines = Files.readAllLines(file);
                    for (int i = 0; i < lines.size(); i++) {
                        if (lines.get(i).contains("Fcfa")) {
                            offenders.add(file + ":" + (i + 1) + " " + lines.get(i).trim());
                        }
                    }
                }
            }
        }
        assertThat(offenders)
                .as("identifiants qui nomment la devise (suffixe Fcfa)")
                .isEmpty();
    }
}
