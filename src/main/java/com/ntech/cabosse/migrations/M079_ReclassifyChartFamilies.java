package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.WriteModel;
import com.ntech.cabosse.accounting.entity.AccountFamily;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * Migration 079 — la famille d'un compte redevient sa classe SYSCOHADA.
 *
 * <p>Le plan comptable rangeait les comptes dans un regroupement
 * fonctionnel maison — achats, ventes, tiers, trésorerie, autres — qui ne
 * correspondait pas au référentiel révisé. Les conséquences se lisaient
 * directement à l'écran et dans la balance exportée : les comptes de
 * capital et de stock apparaissaient en « charges », faute de mieux, et
 * toute la classe 7 s'affichait en « ventes » alors qu'elle couvre
 * l'ensemble des produits des activités ordinaires. Les classes 3, 8 et 9
 * n'existaient tout simplement pas.</p>
 *
 * <p>Sur un plan comptable, un intitulé approximatif n'est pas une nuance
 * de vocabulaire : c'est une classification fausse, et elle se propage
 * jusqu'aux états remis à l'expert-comptable.</p>
 *
 * <p>La classe est entièrement déterminée par le premier chiffre du
 * numéro. Cette migration la recalcule donc plutôt que de la corriger cas
 * par cas, et le service de création la déduit désormais lui aussi : rien
 * ne peut plus la faire diverger.</p>
 */
/*
 * runAlways : le corps est idempotent puisqu'il ne fait qu'appliquer une
 * dérivation. Le rejeu répare aussi un tenant dont le plan aurait été
 * complété par une migration antérieure au correctif, sans qu'il faille
 * une nouvelle livraison pour lui.
 */
@ChangeUnit(id = "reclassify_chart_families", order = "079", author = "neiba", runAlways = true)
public class M079_ReclassifyChartFamilies {

    private static final String COLLECTION = "chart_of_accounts";

    @Execution
    public void execute(MongoDatabase database) {
        var accounts = database.getCollection(COLLECTION);
        List<WriteModel<Document>> ops = new ArrayList<>();

        for (Document account : accounts.find()) {
            String number = account.getString("number");
            AccountFamily expected = AccountFamily.fromNumber(number);
            // Un numéro hors référentiel laisse la famille vide plutôt que
            // d'inventer une classe : une case vide se voit, une classe
            // fausse se recopie.
            String value = expected == null ? null : expected.name();
            if (java.util.Objects.equals(account.getString("family"), value)) continue;
            ops.add(new UpdateOneModel<>(
                    Filters.eq("_id", account.get("_id")),
                    value == null ? Updates.unset("family") : Updates.set("family", value)));
        }

        if (!ops.isEmpty()) accounts.bulkWrite(ops);
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        // Rien à défaire : l'ancienne classification était fausse, la
        // rétablir n'aurait aucun sens.
    }
}
