package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;

/**
 * Migration 077 — la demande d'avance se sépare de son approbation.
 *
 * <p>Une avance à un délégué se créait en un seul geste : un droit, une
 * saisie, et l'écriture comptable partait dans la foulée. C'est pourtant
 * la plus grosse sortie de trésorerie d'une campagne. Un crédit de deux
 * cent mille francs à un producteur passait par trois mains ; une avance
 * de vingt millions à un délégué n'en demandait qu'une.</p>
 *
 * <p>Le droit unique devient trois : demander, approuver, décaisser. Les
 * profils qui pouvaient créer une avance reçoivent <strong>le seul droit
 * de demander</strong>. Leur accorder aussi l'approbation reviendrait à
 * défaire le découpage au moment même de le poser : l'administrateur
 * attribue les deux autres à qui la structure décide.</p>
 *
 * <p>Aucune avance existante ne change d'état : elles ont été décaissées,
 * elles restent ouvertes ou closes. Le nouveau circuit ne vaut que pour
 * les demandes à venir.</p>
 */
@ChangeUnit(id = "split_advance_permissions", order = "077", author = "neiba")
public class M077_SplitAdvancePermissions {

    private static final String ROLES = "tenant_roles";
    private static final String OLD = "COLLECTION_ADVANCE_WRITE";
    private static final String NEW = "COLLECTION_ADVANCE_REQUEST";

    @Execution
    public void execute(MongoDatabase database) {
        var roles = database.getCollection(ROLES);

        // Le droit de demander remplace l'ancien, sur les profils qui
        // l'avaient et qui ne l'ont pas déjà.
        roles.updateMany(
                Filters.and(Filters.eq("permissions", OLD), Filters.ne("permissions", NEW)),
                Updates.push("permissions", NEW));

        // L'ancien code ne désigne plus rien : le laisser encombrerait les
        // profils d'un droit que le catalogue ne reconnaît plus.
        roles.updateMany(
                Filters.eq("permissions", OLD),
                Updates.pull("permissions", OLD));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        var roles = database.getCollection(ROLES);
        roles.updateMany(
                Filters.and(Filters.eq("permissions", NEW), Filters.ne("permissions", OLD)),
                Updates.push("permissions", OLD));
        roles.updateMany(Filters.eq("permissions", NEW), Updates.pull("permissions", NEW));
    }
}
