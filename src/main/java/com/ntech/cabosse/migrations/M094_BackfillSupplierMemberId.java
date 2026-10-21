package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

/**
 * Migration 094 — le sociétaire derrière son fournisseur miroir.
 *
 * <p>Rien ne distinguait un planteur d'un garagiste dans une liste de
 * fournisseurs : les miroirs ne portaient qu'une note en texte libre,
 * « Miroir membre … » (signalé le 21/10/2026). Le registre des membres
 * connaît pourtant son miroir ({@code supplierId}) : chaque fiche
 * fournisseur reçoit en retour l'identifiant du sociétaire, et une
 * liste peut dire d'un coup d'œil qui est producteur.</p>
 *
 * <p>{@code runAlways} : rejouée à chaque démarrage, elle ne pose le
 * champ que là où il manque.</p>
 */
@ChangeUnit(id = "backfill_supplier_member_id", order = "094", author = "neiba",
        runAlways = true)
public class M094_BackfillSupplierMemberId {

    private static final String MEMBERS = "members";
    private static final String SUPPLIERS = "suppliers";

    @Execution
    public void execute(MongoDatabase database) {
        var suppliers = database.getCollection(SUPPLIERS);
        for (Document member : database.getCollection(MEMBERS)
                .find(Filters.exists("supplierId", true))
                .projection(new Document("supplierId", 1))) {
            Object supplierId = member.get("supplierId");
            if (supplierId == null) continue;
            suppliers.updateOne(
                    Filters.and(Filters.eq("_id", supplierId), Filters.exists("memberId", false)),
                    Updates.set("memberId", member.get("_id")));
        }
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        // Le champ est une lecture du registre des membres : le laisser ne
        // trompe personne, le retirer referait disparaître la distinction.
    }
}
