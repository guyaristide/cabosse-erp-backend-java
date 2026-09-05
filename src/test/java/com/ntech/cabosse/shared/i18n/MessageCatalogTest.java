package com.ntech.cabosse.shared.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parité des catalogues de validation FR / EN.
 *
 * <p>Le français est le catalogue par défaut ; l'anglais doit le suivre à
 * la clé près, et toute clé {@code {v.*}} référencée dans une annotation
 * doit exister. Une clé manquante ne casse rien à l'exécution : Hibernate
 * Validator affiche la clé brute à l'utilisateur, ce qui est pire qu'une
 * erreur de build.</p>
 */
class MessageCatalogTest {

    private static Properties load(String name) throws IOException {
        Properties p = new Properties();
        try (InputStream in = MessageCatalogTest.class.getResourceAsStream("/" + name)) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    void the_english_catalog_mirrors_the_french_one() throws IOException {
        Set<String> fr = new TreeSet<>(load("ValidationMessages.properties").stringPropertyNames());
        Set<String> en = new TreeSet<>(load("ValidationMessages_en.properties").stringPropertyNames());

        Set<String> missingInEn = new TreeSet<>(fr); missingInEn.removeAll(en);
        Set<String> missingInFr = new TreeSet<>(en); missingInFr.removeAll(fr);
        assertThat(missingInEn).as("clés absentes du catalogue anglais").isEmpty();
        assertThat(missingInFr).as("clés absentes du catalogue français").isEmpty();
    }

    @Test
    void the_english_business_catalog_mirrors_the_french_one() throws IOException {
        Set<String> fr = new TreeSet<>(load("messages.properties").stringPropertyNames());
        Set<String> en = new TreeSet<>(load("messages_en.properties").stringPropertyNames());

        Set<String> missingInEn = new TreeSet<>(fr); missingInEn.removeAll(en);
        Set<String> missingInFr = new TreeSet<>(en); missingInFr.removeAll(fr);
        assertThat(missingInEn).as("clés absentes du catalogue anglais").isEmpty();
        assertThat(missingInFr).as("clés absentes du catalogue français").isEmpty();
    }

    @Test
    void every_referenced_business_key_exists() throws IOException {
        Set<String> fr = load("messages.properties").stringPropertyNames();
        Pattern ref = Pattern.compile("Messages\\.msg\\(\\s*\"([^\"]+)\"");
        Set<String> missing = new TreeSet<>();

        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            sources.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                String content;
                try {
                    content = Files.readString(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                Matcher m = ref.matcher(content);
                while (m.find()) {
                    if (!fr.contains(m.group(1))) missing.add(m.group(1) + " (" + p + ")");
                }
            });
        }
        assertThat(missing).as("clés Messages.msg sans entrée au catalogue").isEmpty();
    }

    @Test
    void every_referenced_validation_key_exists() throws IOException {
        Set<String> fr = load("ValidationMessages.properties").stringPropertyNames();
        Pattern ref = Pattern.compile("message\\s*=\\s*\"\\{(v\\.[^}]+)}\"");
        Set<String> missing = new TreeSet<>();

        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            sources.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                String content;
                try {
                    content = Files.readString(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                Matcher m = ref.matcher(content);
                while (m.find()) {
                    if (!fr.contains(m.group(1))) missing.add(m.group(1) + " (" + p + ")");
                }
            });
        }
        assertThat(missing).as("clés référencées sans entrée au catalogue").isEmpty();
    }

    /**
     * Apostrophes des messages paramétrés.
     *
     * <p>{@link java.text.MessageFormat} traite l'apostrophe comme un
     * caractère d'échappement <b>dès qu'un message porte un paramètre</b> :
     * « Matières d'origine : {0} » sort « Matières dorigine : {0} », sans
     * l'apostrophe et surtout sans jamais remplacer le paramètre. Rien ne
     * lève, la phrase s'affiche simplement fausse.</p>
     *
     * <p>Le piège ne se voit qu'à l'exécution et seulement sur les
     * messages paramétrés : dans un message sans paramètre, l'apostrophe
     * simple est correcte et la doubler afficherait deux apostrophes.
     * D'où ce contrôle, plutôt qu'une règle uniforme.</p>
     */
    @Test
    void parameterised_messages_double_their_apostrophes() throws IOException {
        Pattern parameterised = Pattern.compile("\\{\\d+}");
        Pattern loneApostrophe = Pattern.compile("(?<!')'(?!')");
        List<String> offenders = new ArrayList<>();

        for (String bundle : List.of("messages.properties", "messages_en.properties")) {
            Properties props = load(bundle);
            for (String key : props.stringPropertyNames()) {
                String value = props.getProperty(key);
                if (parameterised.matcher(value).find()
                        && loneApostrophe.matcher(value).find()) {
                    offenders.add(bundle + " : " + key + " = " + value);
                }
            }
        }
        assertThat(offenders)
                .as("apostrophe simple dans un message paramétré : doublez-la ('') "
                        + "sinon MessageFormat mange le texte et n'insère plus la valeur")
                .isEmpty();
    }

    /**
     * Le piège symétrique, et il se voit à l'écran.
     *
     * <p>Un message sans paramètre ne passe pas par
     * {@link java.text.MessageFormat} : il sort tel quel. Une apostrophe
     * doublée par réflexe s'affiche donc en double, et l'utilisateur lit
     * « la décision d''approbation ».</p>
     *
     * <p>Relevé le 02/09/2026 par un parcours de bout en bout, sur un
     * message écrit le jour même. Quatre messages en souffraient, dont
     * deux plus anciens : la règle inverse était contrôlée, pas
     * celle-ci.</p>
     */
    @Test
    void messages_without_parameters_keep_a_single_apostrophe() throws IOException {
        Pattern parameterised = Pattern.compile("\\{\\d+}");
        Pattern doubled = Pattern.compile("''");
        List<String> offenders = new ArrayList<>();

        for (String bundle : List.of("messages.properties", "messages_en.properties")) {
            Properties props = load(bundle);
            for (String key : props.stringPropertyNames()) {
                String value = props.getProperty(key);
                if (!parameterised.matcher(value).find() && doubled.matcher(value).find()) {
                    offenders.add(bundle + " : " + key + " = " + value);
                }
            }
        }
        assertThat(offenders)
                .as("apostrophe doublée dans un message sans paramètre : elle s'affichera "
                        + "en double, ce message ne passant pas par MessageFormat")
                .isEmpty();
    }

    /**
     * La devise ne s'écrit jamais en dur dans un message rendu.
     *
     * <p>Règle de la maison, rappelée le 04/09/2026 : c'est un SaaS, la
     * devise est une préférence du tenant. Un en-tête qui veut la citer
     * écrit {@code {currency}}, résolu par {@code Messages} sur la devise
     * de la requête. Vingt-sept messages l'écrivaient en dur.</p>
     */
    @Test
    void no_message_hardcodes_the_currency() throws IOException {
        List<String> offenders = new ArrayList<>();
        // Les messages de validation Jakarta comptent aussi : ils ont
        // échappé au premier passage du 04/09/2026 (« Seuil en FCFA
        // négatif interdit »). Leur interpolation ne connaît pas
        // {currency} : un message de validation ne nomme simplement pas
        // la devise.
        for (String bundle : List.of("messages.properties", "messages_en.properties",
                "ValidationMessages.properties", "ValidationMessages_en.properties")) {
            Properties props = load(bundle);
            for (String key : props.stringPropertyNames()) {
                String value = props.getProperty(key);
                if (value.contains("FCFA") || value.contains("XOF")) {
                    offenders.add(bundle + " : " + key + " = " + value);
                }
            }
        }
        assertThat(offenders)
                .as("devise en dur dans un message : écrire {currency}, résolu sur la "
                        + "devise du tenant par Messages")
                .isEmpty();
    }
}
