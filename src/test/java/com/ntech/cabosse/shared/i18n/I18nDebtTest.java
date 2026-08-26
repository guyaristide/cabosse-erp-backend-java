package com.ntech.cabosse.shared.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Budget de dette i18n du backend, miroir du plafond front.
 *
 * <p>Les messages d'exception métier écrits en dur dans le code ne passent
 * pas par le catalogue {@link Messages} et restent donc français quelle que
 * soit la langue demandée. Les migrer d'un coup n'aurait pas de sens ; ce
 * test plafonne la dette : il échoue si elle grossit, et demande d'abaisser
 * le plafond quand elle baisse, pour que le terrain gagné reste gagné.</p>
 *
 * <p>Forme migrée : {@code new BusinessException(Messages.msg("m.clé", …))}
 * (avec ou sans ErrorCode). Les messages de validation Jakarta sont déjà
 * intégralement au catalogue (ValidationMessages*.properties).</p>
 */
class I18nDebtTest {

    /** Départ 602 (2026-08-26), 171 puis 156 au fil des vagues. À faire baisser, jamais monter. */
    private static final int BUDGET = 156;

    private static final Pattern INLINE_MESSAGE = Pattern.compile(
            "new\\s+(BusinessException|ConflictException|NotFoundException)\\(\\s*\\n?\\s*"
                    + "(ErrorCode\\.[A-Z_]+\\s*,\\s*)?\"");

    @Test
    void inline_exception_messages_do_not_grow() throws IOException {
        Map<String, Integer> perFile = new TreeMap<>();
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            sources.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                String content;
                try {
                    content = Files.readString(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                Matcher m = INLINE_MESSAGE.matcher(content);
                int count = 0;
                while (m.find()) count++;
                if (count > 0) perFile.put(p.toString(), count);
            });
        }
        int total = perFile.values().stream().mapToInt(Integer::intValue).sum();

        assertThat(total)
                .as("messages d'exception en dur (budget %d) — écrivez les nouveaux via "
                        + "Messages.msg(\"m.clé\") + messages(.en).properties. Fichiers les plus "
                        + "chargés : %s",
                        BUDGET,
                        perFile.entrySet().stream()
                                .sorted((a, b) -> b.getValue() - a.getValue())
                                .limit(5).toList())
                .isLessThanOrEqualTo(BUDGET);

        assertThat(total)
                .as("dette retombée à %d : abaissez BUDGET à cette valeur", total)
                .isGreaterThan(BUDGET - 40);
    }
}
