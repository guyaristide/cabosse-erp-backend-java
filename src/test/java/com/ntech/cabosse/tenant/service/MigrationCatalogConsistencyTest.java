package com.ntech.cabosse.tenant.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test unitaire pur — pas de @QuarkusTest, pas de Mongo, pas d'HTTP.
 *
 * <p>Garde-fou anti-dérive : le {@code CATALOG} statique de
 * {@link TenantTechnicalService} (migrations attendues, affichées dans la
 * vue technique M9) doit refléter <strong>exactement</strong> les
 * {@code @ChangeUnit} du package {@code com.ntech.cabosse.migrations}. Sans
 * ce test, ajouter une migration sans mettre à jour le catalogue passe
 * inaperçu — c'est précisément ce qui l'avait laissé bloqué à la seule
 * M001 alors que 24 migrations existaient.</p>
 *
 * <p>On lit la source de vérité dans les fichiers source des migrations
 * (clé = {@code order}, valeur = {@code id}) et on la compare au
 * {@code CATALOG} lu par réflexion — le code de production reste inchangé,
 * aucune API n'est ouverte juste pour le test.</p>
 */
class MigrationCatalogConsistencyTest {

    private static final Path MIGRATIONS_DIR =
            Path.of("src/main/java/com/ntech/cabosse/migrations");

    @Test
    void leCatalogueRefleteExactementLesChangeUnits() throws Exception {
        Map<String, String> fromSources = readChangeUnitsFromSources();
        Map<String, String> fromCatalog = readCatalogByReflection();

        assertThat(fromSources)
                .as("aucune migration scannée — le chemin source est-il correct "
                        + "(cwd = %s) ?", Path.of("").toAbsolutePath())
                .isNotEmpty();

        assertThat(fromCatalog)
                .as("TenantTechnicalService.CATALOG doit refléter les @ChangeUnit du "
                        + "package migrations (clé = order, valeur = id). Toute migration "
                        + "ajoutée ou retirée impose une mise à jour du CATALOG.")
                .isEqualTo(fromSources);
    }

    // ─── Vérité : annotations @ChangeUnit dans les sources ──────────────

    private static Map<String, String> readChangeUnitsFromSources() throws IOException {
        assertThat(Files.isDirectory(MIGRATIONS_DIR))
                .as("dossier des migrations introuvable : %s (cwd = %s)",
                        MIGRATIONS_DIR, Path.of("").toAbsolutePath())
                .isTrue();

        Pattern annotation = Pattern.compile("@ChangeUnit\\s*\\(([^)]*)\\)");
        Pattern idAttr = Pattern.compile("id\\s*=\\s*\"([^\"]+)\"");
        Pattern orderAttr = Pattern.compile("order\\s*=\\s*\"([^\"]+)\"");

        Map<String, String> byOrder = new LinkedHashMap<>();
        List<Path> javaFiles;
        try (Stream<Path> files = Files.list(MIGRATIONS_DIR)) {
            javaFiles = files
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }

        for (Path file : javaFiles) {
            String source = Files.readString(file);
            Matcher ann = annotation.matcher(source);
            if (!ann.find()) {
                continue; // fichier du package sans @ChangeUnit → pas une migration
            }
            String body = ann.group(1);
            Matcher id = idAttr.matcher(body);
            Matcher order = orderAttr.matcher(body);
            assertThat(id.find())
                    .as("attribut id manquant dans @ChangeUnit de %s", file.getFileName())
                    .isTrue();
            assertThat(order.find())
                    .as("attribut order manquant dans @ChangeUnit de %s", file.getFileName())
                    .isTrue();
            String previous = byOrder.put(order.group(1), id.group(1));
            assertThat(previous)
                    .as("order %s dupliqué entre deux migrations", order.group(1))
                    .isNull();
        }
        return byOrder;
    }

    // ─── CATALOG de production, lu sans modifier le code de prod ─────────

    @SuppressWarnings("unchecked")
    private static Map<String, String> readCatalogByReflection() throws Exception {
        Field catalogField = TenantTechnicalService.class.getDeclaredField("CATALOG");
        catalogField.setAccessible(true);
        List<Object> catalog = (List<Object>) catalogField.get(null);

        Map<String, String> byOrder = new LinkedHashMap<>();
        for (Object descriptor : catalog) {
            String id = (String) recordComponent(descriptor, "changeId");
            String order = (String) recordComponent(descriptor, "order");
            String previous = byOrder.put(order, id);
            assertThat(previous)
                    .as("order %s dupliqué dans le CATALOG", order)
                    .isNull();
        }
        return byOrder;
    }

    private static Object recordComponent(Object record, String component) throws Exception {
        Method accessor = record.getClass().getDeclaredMethod(component);
        accessor.setAccessible(true);
        return accessor.invoke(record);
    }
}
