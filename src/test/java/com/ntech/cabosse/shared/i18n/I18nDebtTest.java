package com.ntech.cabosse.shared.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Budget de dette i18n du backend, miroir du plafond front.
 *
 * <p>Les messages écrits en dur dans le code ne passent pas par le
 * catalogue {@link Messages} et restent donc français quelle que soit la
 * langue demandée. Les migrer d'un coup n'aurait pas de sens ; ce test
 * plafonne la dette : il échoue si elle grossit, et demande d'abaisser le
 * plafond quand elle baisse, pour que le terrain gagné reste gagné.</p>
 *
 * <p>Forme migrée : {@code new BusinessException(Messages.msg("m.clé", …))}
 * (avec ou sans ErrorCode). Les messages de validation Jakarta sont déjà
 * intégralement au catalogue (ValidationMessages*.properties).</p>
 *
 * <p><b>Élargissement du 26/08/2026.</b> Le compteur ne regardait que trois
 * types d'exception et ne retirait pas les commentaires : il plafonnait 156
 * sites alors que le budget était déjà atteint, et laissait passer deux
 * familles entières. {@code UnauthorizedException} et {@code ForbiddenException}
 * ont leur propre {@code ExceptionMapper} et leur message part au client ;
 * {@code ValidationException} étend {@code BusinessException} et n'était pas
 * nommée. À l'inverse, {@code IllegalStateException} reste volontairement
 * hors périmètre : {@code ThrowableExceptionMapper} ne renvoie jamais le
 * message interne, ces libellés ne sont donc pas vus par l'utilisateur et
 * les traduire serait du travail perdu.</p>
 */
class I18nDebtTest {

    /**
     * Départ 602 (2026-08-26), 171 puis 156 au fil des vagues. Rebasé à 170
     * le 26/08/2026 : trois types d'exception exposés au client s'ajoutent au
     * compte, et les commentaires en sortent. À faire baisser, jamais monter.
     */
    private static final int BUDGET = 0;

    /**
     * Messages d'anomalie de ligne d'import, affichés un par un dans l'écran
     * de prévisualisation. Budget séparé : c'est une autre famille de travail,
     * et les mélanger empêcherait de voir laquelle avance.
     */
    private static final int FIELD_ISSUE_BUDGET = 0;

    /** Types dont le message remonte au client via un ExceptionMapper dédié. */
    private static final Pattern INLINE_MESSAGE = Pattern.compile(
            "new\\s+(BusinessException|ConflictException|NotFoundException"
                    + "|UnauthorizedException|ForbiddenException|ValidationException)"
                    + "\\(\\s*\\n?\\s*(ErrorCode\\.[A-Z_]+\\s*,\\s*)?\"");

    /**
     * En-tête de colonne d'export écrit en dur.
     *
     * <p>Un export part chez l'utilisateur, et pour les parcelles et les
     * récoltes il lui revient : ses en-têtes doivent donc suivre la même
     * langue que le modèle d'import, sans quoi le fichier corrigé sur le
     * terrain ne se relit plus. La forme migrée est
     * {@code ExportColumn.of(Messages.msg("m.imp-h-…"), …)}, ou la même
     * avec une clé de colonne devant.</p>
     *
     * <p>Le slug de clé, lui, reste un littéral : il identifie la colonne
     * dans une sélection d'export et ne s'affiche jamais. D'où la
     * négation qui exige une lettre accentuée, une majuscule ou une
     * espace pour ne retenir que ce qui se lit.</p>
     */
    private static final Pattern INLINE_EXPORT_HEADER = Pattern.compile(
            "ExportColumn\\.of\\(\\s*\"[a-z0-9-]+\"\\s*,\\s*\"[^\"]*[A-ZÀ-ÿ ][^\"]*\""
                    + "|ExportColumn\\.of\\(\\s*\"[^\"]*[A-ZÀ-ÿ ][^\"]*\"\\s*,(?!\\s*\")");

    /** {@code new FieldIssue("champ", "message en dur")}. */
    private static final Pattern INLINE_FIELD_ISSUE = Pattern.compile(
            "new\\s+FieldIssue\\(\\s*\\n?\\s*[^,)]+,\\s*\\n?\\s*\"");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("^\\s*//.*$", Pattern.MULTILINE);

    @Test
    void inline_exception_messages_do_not_grow() throws IOException {
        assertBudget(INLINE_MESSAGE, BUDGET,
                "messages d'exception en dur (budget %d) — écrivez les nouveaux via "
                        + "Messages.msg(\"m.clé\") + messages(.en).properties");
    }

    @Test
    void export_headers_come_from_the_catalog() throws IOException {
        assertBudget(INLINE_EXPORT_HEADER, 0,
                "en-têtes de colonnes d'export en dur (budget %d) — écrivez-les via "
                        + "Messages.msg(\"m.imp-h-clé\"), pour que l'export et le modèle "
                        + "d'import parlent la même langue");
    }

    @Test
    void inline_field_issue_messages_do_not_grow() throws IOException {
        assertBudget(INLINE_FIELD_ISSUE, FIELD_ISSUE_BUDGET,
                "messages FieldIssue en dur (budget %d) — écrivez les nouveaux via "
                        + "Messages.msg(\"m.clé\"), comme Parcel et Harvest le font déjà");
    }

    private void assertBudget(Pattern pattern, int budget, String label) throws IOException {
        Map<String, Integer> perFile = countMatches(pattern);
        int total = perFile.values().stream().mapToInt(Integer::intValue).sum();

        assertThat(total)
                .as(label + ". Fichiers les plus chargés : %s",
                        budget,
                        perFile.entrySet().stream()
                                .sorted((a, b) -> b.getValue() - a.getValue())
                                .limit(5).toList())
                .isLessThanOrEqualTo(budget);

        // Le plancher force à répercuter les gains dans le budget, sinon il
        // cesse d'exercer une pression. Sans objet une fois la dette éteinte :
        // il n'y a plus rien à abaisser, seulement une régression à empêcher.
        if (budget > 0) {
            assertThat(total)
                    .as("dette retombée à %d : abaissez le budget à cette valeur", total)
                    .isGreaterThan(budget - 40);
        }
    }

    private Map<String, Integer> countMatches(Pattern pattern) throws IOException {
        Map<String, Integer> perFile = new TreeMap<>();
        Function<Path, String> read = p -> {
            try {
                String content = Files.readString(p);
                // Un exemple de code en Javadoc n'est pas une chaîne livrée.
                content = BLOCK_COMMENT.matcher(content).replaceAll("");
                return LINE_COMMENT.matcher(content).replaceAll("");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            sources.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                Matcher m = pattern.matcher(read.apply(p));
                int count = 0;
                while (m.find()) count++;
                if (count > 0) perFile.put(p.toString(), count);
            });
        }
        return perFile;
    }
}
