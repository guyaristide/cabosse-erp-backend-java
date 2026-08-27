package com.ntech.cabosse.agriculture;

import com.ntech.cabosse.shared.i18n.Messages;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L'export et le modèle d'import parlent des mêmes colonnes.
 *
 * <p>Le circuit qui compte sur le terrain est : exporter ses parcelles,
 * corriger le fichier, le redéposer. Il ne tient que si l'export écrit
 * exactement les en-têtes que la relecture attend. L'export les portait
 * en dur en français pendant que le modèle d'import venait déjà du
 * catalogue : un utilisateur anglophone téléchargeait donc un modèle
 * anglais et récupérait un export français, deux fichiers censés être le
 * même.</p>
 *
 * <p>Ce test compare les clés employées de part et d'autre plutôt que les
 * chaînes rendues : c'est ce qui rend l'alignement vrai dans toutes les
 * langues à la fois, y compris celles qui viendront.</p>
 */
class ExportImportHeaderAlignmentTest {

    private static final Pattern KEY = Pattern.compile("\"(m\\.imp-h-[a-z0-9-]+)\"");

    private static Set<String> keysOf(String path) throws IOException {
        String source = Files.readString(Path.of(path));
        Matcher m = KEY.matcher(source);
        Set<String> keys = new LinkedHashSet<>();
        while (m.find()) keys.add(m.group(1));
        return keys;
    }

    /**
     * Colonnes que l'export ajoute et que le modèle n'a pas : elles sont
     * ignorées à la relecture, mais l'export sert aussi d'état de synthèse.
     */
    private static final Set<String> EXPORT_ONLY_PARCEL = Set.of(
            "m.imp-h-parcel-planting-year", "m.imp-h-certifications", "m.imp-h-producer-name");

    private static final Set<String> EXPORT_ONLY_HARVEST = Set.of(
            "m.imp-h-harvest-code", "m.imp-h-campaign", "m.imp-h-parcel-name",
            "m.imp-h-producer-name");

    @Test
    void the_parcel_export_names_its_columns_like_the_import_template() throws IOException {
        Set<String> exportKeys = keysOf(
                "src/main/java/com/ntech/cabosse/agriculture/parcel/controller/ParcelExportColumns.java");
        Set<String> templateKeys = keysOf(
                "src/main/java/com/ntech/cabosse/agriculture/parcel/controller/ParcelImportTemplate.java");

        assertThat(exportKeys).as("l'export doit passer par le catalogue").isNotEmpty();
        assertThat(exportKeys).as("colonnes de l'export inconnues du modèle")
                .isSubsetOf(union(templateKeys, EXPORT_ONLY_PARCEL));
    }

    @Test
    void the_harvest_export_names_its_columns_like_the_import_template() throws IOException {
        Set<String> exportKeys = keysOf(
                "src/main/java/com/ntech/cabosse/agriculture/harvest/controller/HarvestExportColumns.java");
        Set<String> templateKeys = keysOf(
                "src/main/java/com/ntech/cabosse/agriculture/harvest/controller/HarvestImportTemplate.java");

        assertThat(exportKeys).as("l'export doit passer par le catalogue").isNotEmpty();
        assertThat(exportKeys).as("colonnes de l'export inconnues du modèle")
                .isSubsetOf(union(templateKeys, EXPORT_ONLY_HARVEST));
    }

    @Test
    void every_column_key_reads_in_both_languages() throws IOException {
        Set<String> keys = union(
                keysOf("src/main/java/com/ntech/cabosse/agriculture/parcel/controller/ParcelExportColumns.java"),
                keysOf("src/main/java/com/ntech/cabosse/agriculture/harvest/controller/HarvestExportColumns.java"));

        for (String key : keys) {
            for (Locale locale : List.of(Locale.FRENCH, Locale.ENGLISH)) {
                assertThat(Messages.msg(locale, key))
                        .as("%s en %s", key, locale)
                        .isNotBlank()
                        .isNotEqualTo(key);
            }
        }
    }

    @Test
    void yes_and_no_are_translated_and_still_readable_on_reimport() {
        assertThat(Messages.msg(Locale.FRENCH, "m.imp-v-yes")).isEqualTo("Oui");
        assertThat(Messages.msg(Locale.ENGLISH, "m.imp-v-yes")).isEqualTo("Yes");
        // « yes » doit rester dans les formes acceptées par ParcelImportService,
        // sinon la colonne « culture principale » revient muette d'un
        // aller-retour en anglais.
        assertThat(Messages.msg(Locale.ENGLISH, "m.imp-v-yes").toLowerCase(Locale.ROOT))
                .startsWith("yes");
        assertThat(Messages.msg(Locale.FRENCH, "m.imp-v-yes").toLowerCase(Locale.ROOT))
                .startsWith("oui");
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> all = new LinkedHashSet<>(a);
        all.addAll(b);
        return all;
    }
}
