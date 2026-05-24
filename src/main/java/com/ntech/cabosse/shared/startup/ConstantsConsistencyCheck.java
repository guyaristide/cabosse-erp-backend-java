package com.ntech.cabosse.shared.startup;

import com.ntech.cabosse.shared.persistence.CacheNames;
import io.quarkus.cache.CacheManager;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Vérification de cohérence au démarrage de l'application.
 *
 * <p>Garantit que :
 * <ul>
 *   <li>Tous les noms de cache déclarés dans
 *       {@link CacheNames} sont effectivement configurés
 *       dans {@code application.yml} sous {@code quarkus.cache.caffeine.*}.
 *       Sans ça, un appel {@code @CacheResult} échouerait silencieusement
 *       au runtime au lieu d'au boot.</li>
 *   <li>(Phase B+) Tous les rôles assignés à un utilisateur en base sont
 *       connus de {@link com.ntech.cabosse.shared.security.Roles}.
 *       Cette portion attend l'arrivée du {@code UserRepository}.</li>
 * </ul>
 *
 * <p>Le démarrage <strong>échoue</strong> si une incohérence est détectée
 * (cf. shared-constants.md §10). Pas de runtime surprise.</p>
 */
@ApplicationScoped
public class ConstantsConsistencyCheck {

    @Inject
    CacheManager cacheManager;

    @Inject
    Logger log;

    void onStart(@Observes StartupEvent ev) {
        log.info("Constants consistency check — start");
        checkCacheNames();
        log.info("Constants consistency check — OK");
    }

    private void checkCacheNames() {
        for (Field f : CacheNames.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                try {
                    String name = (String) f.get(null);
                    if (cacheManager.getCache(name).isEmpty()) {
                        throw new IllegalStateException(
                                "Cache '" + name + "' référencé dans CacheNames mais "
                                        + "absent de application.yml (quarkus.cache.caffeine."
                                        + name + ")."
                        );
                    }
                } catch (IllegalAccessException e) {
                    // Ne devrait pas arriver : tous les champs sont publics.
                    throw new IllegalStateException(
                            "Accès interdit au champ " + f.getName() + " de CacheNames", e
                    );
                }
            }
        }
    }
}
