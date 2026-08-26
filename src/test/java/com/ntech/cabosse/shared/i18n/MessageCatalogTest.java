package com.ntech.cabosse.shared.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
