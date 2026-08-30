package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.ntech.cabosse.permission.entity.Permission;
import com.ntech.cabosse.shared.migration.CapabilityMigrationGuard;
import com.ntech.cabosse.tenant.capability.TenantCapability;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Migration 082 — le profil Administrateur porte tout le catalogue, et le
 * garde.
 *
 * <p>Il est semé avec l'ensemble des droits ouverts à la structure, mais
 * <strong>une seule fois</strong> : le semis s'arrête net si le profil
 * existe déjà. Un droit ajouté au produit après l'ouverture de la
 * structure ne l'atteignait donc jamais. C'est ce qui vient de se
 * produire avec la consultation du journal d'audit, et cela se serait
 * reproduit à chaque nouvelle permission — une migration de rattrapage à
 * écrire à chaque fois, et un oubli qui se paie par un administrateur
 * privé d'un écran sans savoir pourquoi.</p>
 *
 * <p>Cette migration remet donc au profil ce qui lui manque, à chaque
 * démarrage. Elle <strong>ajoute seulement</strong> : ce que la structure
 * a pu configurer par ailleurs n'est pas écrasé, et un droit devenu sans
 * objet est de toute façon écarté à la résolution, en fonction des
 * capacités du moment.</p>
 */
/*
 * runAlways : c'est tout l'intérêt. Le rejeu est ce qui fait qu'un droit
 * livré demain rejoint le profil au redémarrage suivant, sans migration
 * dédiée. Le corps est idempotent — il ne fait rien quand rien ne manque.
 */
@ChangeUnit(id = "keep_administrator_profile_complete", order = "082", author = "neiba", runAlways = true)
public class M082_KeepAdministratorProfileComplete {

    private static final String ROLES = "tenant_roles";
    private static final String ADMINISTRATOR_CODE = "ADMINISTRATEUR";

    @Execution
    public void execute(MongoDatabase database, MongoClient client) {
        var roles = database.getCollection(ROLES);
        Document administrator = roles.find(Filters.eq("code", ADMINISTRATOR_CODE)).first();
        // Pas de profil administrateur : c'est au semis de le créer, pas
        // à cette migration de l'inventer.
        if (administrator == null) return;

        Set<TenantCapability> caps =
                CapabilityMigrationGuard.capabilitiesFor(database.getName(), client);

        @SuppressWarnings("unchecked")
        List<String> held = administrator.get("permissions") instanceof List<?> l
                ? (List<String>) l : List.of();

        // Une case sans objet n'est pas un droit : le profil ne porte que
        // ce que les capacités de la structure rendent applicable.
        List<String> missing = new ArrayList<>();
        for (Permission p : Permission.values()) {
            if (p.availableFor(caps) && !held.contains(p.name())) {
                missing.add(p.name());
            }
        }
        if (missing.isEmpty()) return;

        roles.updateOne(
                Filters.eq("_id", administrator.get("_id")),
                Updates.addEachToSet("permissions", missing));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        // Rien à défaire : retirer des droits à l'administrateur le
        // priverait d'écrans qu'il administre.
    }
}
