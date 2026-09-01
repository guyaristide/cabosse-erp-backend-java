package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.ntech.cabosse.shared.persistence.ControlPlane;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

/**
 * Donne une portée aux fournisseurs d'envoi déjà déclarés.
 *
 * <p>Jusqu'ici un fournisseur servait indistinctement toutes les
 * structures. Depuis que chacune peut déclarer les siens, il faut dire à
 * quel niveau vivent ceux qui existaient : ce sont des fournisseurs de la
 * plateforme, et rien ne doit changer pour eux.</p>
 *
 * <p>Rejouable : seuls les documents dépourvus de portée sont touchés, si
 * bien qu'une seconde exécution ne défait pas un fournisseur qu'une
 * coopérative aurait déclaré entre-temps.</p>
 */
@ChangeUnit(id = "scope_notification_providers", order = "083", author = "neiba")
public class M083_ScopeNotificationProviders {

    @Execution
    public void execute(MongoDatabase database, MongoClient client) {
        client.getDatabase(ControlPlane.DATABASE)
                .getCollection(ControlPlane.Collections.NOTIFICATION_PROVIDERS)
                .updateMany(
                        Filters.exists("scope", false),
                        Updates.set("scope", "PLATFORM"));
    }

    /**
     * Rien à défaire. Retirer la portée rendrait les fournisseurs
     * invisibles au résolveur, qui filtre désormais dessus : une structure
     * se retrouverait sans canal d'envoi pour avoir annulé une migration.
     */
    @RollbackExecution
    public void rollback() {
        // Volontairement vide.
    }
}
