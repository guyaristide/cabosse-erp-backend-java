package com.ntech.cabosse.health;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.InputStream;
import java.time.Instant;
import java.util.Properties;

/**
 * L'identité du code qui tourne : version, commit, branche, heure de build.
 *
 * <p>Elle est scellée dans le jar à la compilation (voir {@code build.gradle},
 * tâche {@code processResources}) et relue ici au démarrage. Une valeur
 * absente vaut « inconnu » : mieux vaut l'admettre que laisser croire à une
 * version identifiée.</p>
 *
 * <p>L'heure de démarrage complète le tableau : deux environnements peuvent
 * porter le même commit sans avoir redémarré depuis le même déploiement, et
 * c'est le redémarrage qui applique les migrations.</p>
 */
@ApplicationScoped
public class BuildInfo {

    private static final String UNKNOWN = "inconnu";

    private String version = UNKNOWN;
    private String commit = UNKNOWN;
    private String branch = UNKNOWN;
    private String builtAt = UNKNOWN;
    private final Instant startedAt = Instant.now();

    @PostConstruct
    void load() {
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/build-info.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (Exception ignored) {
            // Un jar sans identité de build reste un jar qui démarre : on
            // dégrade la réponse, on ne refuse pas de servir.
        }
        version = props.getProperty("build.version", UNKNOWN);
        commit = props.getProperty("build.commit", UNKNOWN);
        branch = props.getProperty("build.branch", UNKNOWN);
        builtAt = props.getProperty("build.time", UNKNOWN);
    }

    public String version() { return version; }

    public String commit() { return commit; }

    public String branch() { return branch; }

    public String builtAt() { return builtAt; }

    public Instant startedAt() { return startedAt; }
}
