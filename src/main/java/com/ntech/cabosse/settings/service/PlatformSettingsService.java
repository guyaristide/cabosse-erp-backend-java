package com.ntech.cabosse.settings.service;

import com.ntech.cabosse.settings.entity.PlatformSettingEntity;
import com.ntech.cabosse.settings.repository.PlatformSettingsRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Accès central aux paramètres plateforme. Pour chaque clé :
 * <ol>
 *   <li>cherche en BD ({@link PlatformSettingsRepository}, cache mémoire),</li>
 *   <li>à défaut, lit la propriété MicroProfile Config (fallback YAML/env).</li>
 * </ol>
 *
 * <p>Le cache est invalidé à chaque écriture (par section). Pas de TTL —
 * les paramètres changent rarement, et le cache mémoire suffit (mono-instance
 * au MVP). Pour du multi-instance plus tard, ajouter une diffusion d'événement
 * d'invalidation (Mongo change stream ou Redis pub/sub).</p>
 *
 * <p>Les valeurs marquées comme secrètes sont déchiffrées transparently
 * en lecture. Le service expose aussi {@link #rawValuesForAdmin(String)}
 * qui ne déchiffre PAS et permet à la couche resource de renvoyer des
 * valeurs masquées sans jamais voir les secrets en clair.</p>
 */
@ApplicationScoped
public class PlatformSettingsService {

    @Inject PlatformSettingsRepository repo;
    @Inject SecretCipher cipher;
    @Inject Config config;
    @Inject Logger log;

    /** Snapshot section → values déchiffrées. Reconstruit à chaque écriture. */
    private final Map<String, Map<String, String>> cache = new ConcurrentHashMap<>();

    // ─── Lecture ─────────────────────────────────────────────────────

    /**
     * Renvoie la valeur effective pour {@code section.key}. Priorité BD,
     * fallback {@code configKey} ({@link Config}). {@code null} si rien.
     */
    public String get(String section, String key, String configKey) {
        String fromDb = readFromDb(section).get(key);
        if (fromDb != null && !fromDb.isBlank()) return fromDb;
        if (configKey == null) return null;
        return config.getOptionalValue(configKey, String.class).orElse(null);
    }

    /**
     * Comme {@link #get} mais retourne aussi la source effective.
     * Utile au front pour afficher "source : BD" ou "source : fichier".
     */
    public ValueWithSource resolve(String section, String key, String configKey) {
        String fromDb = readFromDb(section).get(key);
        if (fromDb != null && !fromDb.isBlank()) {
            return new ValueWithSource(fromDb, Source.DATABASE);
        }
        if (configKey != null) {
            Optional<String> fromYaml = config.getOptionalValue(configKey, String.class);
            if (fromYaml.isPresent()) return new ValueWithSource(fromYaml.get(), Source.FALLBACK);
        }
        return new ValueWithSource(null, Source.NONE);
    }

    /** Vraies valeurs de la BD (déchiffrées si secrètes). Map vide si absent. */
    public Map<String, String> readFromDb(String section) {
        return cache.computeIfAbsent(section, this::loadFromDb);
    }

    /**
     * Renvoie la map brute (secrets encore chiffrés). Réservé à la couche
     * resource pour produire des valeurs masquées — JAMAIS pour usage métier.
     */
    public RawSection rawValuesForAdmin(String section) {
        PlatformSettingEntity entity = repo.findById(section);
        if (entity == null) {
            return new RawSection(new HashMap<>(), new HashSet<>(), null, null);
        }
        return new RawSection(
                new HashMap<>(entity.values),
                new HashSet<>(entity.secretKeys != null ? entity.secretKeys : Set.of()),
                entity.updatedAt,
                entity.updatedBy
        );
    }

    private Map<String, String> loadFromDb(String section) {
        PlatformSettingEntity entity = repo.findById(section);
        if (entity == null) return Map.of();
        Set<String> secrets = entity.secretKeys != null ? entity.secretKeys : Set.of();
        Map<String, String> resolved = new HashMap<>();
        for (Map.Entry<String, String> e : entity.values.entrySet()) {
            String v = e.getValue();
            if (v != null && secrets.contains(e.getKey())) {
                try {
                    v = cipher.decrypt(v);
                } catch (Exception ex) {
                    log.errorf(ex, "Impossible de déchiffrer %s.%s : valeur ignorée.", section, e.getKey());
                    continue;
                }
            }
            resolved.put(e.getKey(), v);
        }
        return resolved;
    }

    // ─── Écriture ────────────────────────────────────────────────────

    /**
     * Écrit/met à jour une section. {@code values} contient le contenu
     * complet à persister ; les clés présentes dans {@code secretKeyNames}
     * sont chiffrées avant stockage.
     *
     * <p>Comportement « patch sensible » : si une valeur secrète est
     * fournie vide ({@code null} ou {@code ""}), on conserve la valeur
     * existante en BD. Permet à l'UI de renvoyer la version masquée sans
     * écraser le vrai secret par accident. Pour effacer un secret,
     * passer la chaîne littérale {@code "<<clear>>"}.</p>
     */
    @Transactional
    public void writeSection(String section, Map<String, String> values, Set<String> secretKeyNames, String updatedBy) {
        if (section == null || section.isBlank()) {
            throw new IllegalArgumentException("Section requise.");
        }
        PlatformSettingEntity existing = repo.findById(section);
        Map<String, String> existingValues = existing != null ? existing.values : Map.of();

        Map<String, String> toStore = new HashMap<>();
        Set<String> secrets = new HashSet<>(secretKeyNames != null ? secretKeyNames : Set.of());

        for (Map.Entry<String, String> e : values.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            boolean isSecret = secrets.contains(key);

            if (isSecret) {
                if (value == null || value.isBlank()) {
                    // valeur vide → conserver l'existant
                    String prev = existingValues.get(key);
                    if (prev != null) toStore.put(key, prev);
                    continue;
                }
                if ("<<clear>>".equals(value)) {
                    // sentinelle d'effacement explicite
                    continue;
                }
                toStore.put(key, cipher.encrypt(value));
            } else {
                if (value == null) continue;
                toStore.put(key, value);
            }
        }

        if (existing == null) {
            PlatformSettingEntity entity = new PlatformSettingEntity();
            entity.section = section;
            entity.values = toStore;
            entity.secretKeys = secrets;
            entity.updatedAt = Instant.now();
            entity.updatedBy = updatedBy;
            repo.persist(entity);
        } else {
            existing.values = toStore;
            existing.secretKeys = secrets;
            existing.updatedAt = Instant.now();
            existing.updatedBy = updatedBy;
            repo.update(existing);
        }

        // invalidate cache
        cache.remove(section);
    }

    /** Vide tout le cache (utile en test). */
    public void invalidateAll() {
        cache.clear();
    }

    // ─── Types de retour ─────────────────────────────────────────────

    public enum Source { DATABASE, FALLBACK, NONE }

    public record ValueWithSource(String value, Source source) {
        public boolean fromDb()       { return source == Source.DATABASE; }
        public boolean fromFallback() { return source == Source.FALLBACK; }
    }

    public record RawSection(Map<String, String> values, Set<String> secretKeys,
                             Instant updatedAt, String updatedBy) {

        public boolean exists() { return !values.isEmpty(); }
    }
}
