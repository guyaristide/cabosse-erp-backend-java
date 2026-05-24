package com.ntech.cabosse.test;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

/**
 * Ressource de test Testcontainers MongoDB en mode <strong>replica set</strong>.
 *
 * <p>Le replica set est obligatoire parce que :
 * <ul>
 *   <li>Mongock exige un changelog cohérent avec lock distribué — ce qui
 *       n'est pas garanti sur MongoDB standalone.</li>
 *   <li>Les transactions multi-documents (utilisées par certains services
 *       en Phase B+) exigent un replica set.</li>
 *   <li>On veut tester sur la même topologie qu'en prod
 *       (cf. multi-tenant-architecture.md §14.2 / §14.3).</li>
 * </ul>
 *
 * <p>{@code withReuse(true)} accélère les tests en gardant le container
 * vivant entre les builds ; voir la doc Testcontainers reuse pour
 * activer en local (créer {@code ~/.testcontainers.properties} avec
 * {@code testcontainers.reuse.enable=true}).</p>
 */
public class MongoReplicaSetTestResource implements QuarkusTestResourceLifecycleManager {

    private MongoDBContainer container;

    @Override
    public Map<String, String> start() {
        container = new MongoDBContainer(DockerImageName.parse("mongo:7.0"))
                .withReuse(true);
        container.start();
        return Map.of(
                "quarkus.mongodb.connection-string", container.getReplicaSetUrl()
        );
    }

    @Override
    public void stop() {
        if (container != null && !container.isShouldBeReused()) {
            container.stop();
        }
    }
}
