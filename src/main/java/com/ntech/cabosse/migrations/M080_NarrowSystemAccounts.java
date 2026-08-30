package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.WriteModel;
import com.ntech.cabosse.accounting.entity.SyscohadaAccounts;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * Migration 080 — le verrou du plan comptable se resserre sur ce qui le
 * mérite.
 *
 * <p>Tout compte semé par une migration était marqué « du socle » et ne
 * pouvait donc pas être retiré des saisies. Sur un plan de soixante-sept
 * comptes, cinquante-six étaient verrouillés alors que le moteur de
 * comptabilisation n'en emploie qu'une trentaine. Une structure qui ne
 * fait pas de production, ou qui n'a qu'une banque, se retrouvait avec des
 * comptes qu'elle ne pouvait ni utiliser ni écarter, et une liste de choix
 * encombrée à chaque saisie.</p>
 *
 * <p>Le verrou garde son sens d'origine : un compte que le moteur emploie
 * <em>sans que personne ne le choisisse</em> ne peut pas disparaître, sous
 * peine de faire échouer un achat ou une vente au moment de passer
 * l'écriture. Mais il ne protège plus que ceux-là. Le reste du plan
 * standard appartient à la structure.</p>
 */
/*
 * runAlways : le corps applique une dérivation à partir de la liste des
 * comptes du moteur. Le rejeu reprotège automatiquement un compte ajouté
 * au moteur depuis, et libère celui qui n'y est plus.
 */
@ChangeUnit(id = "narrow_system_accounts", order = "080", author = "neiba", runAlways = true)
public class M080_NarrowSystemAccounts {

    private static final String COLLECTION = "chart_of_accounts";

    @Execution
    public void execute(MongoDatabase database) {
        var accounts = database.getCollection(COLLECTION);
        List<WriteModel<Document>> ops = new ArrayList<>();

        for (Document account : accounts.find()) {
            String number = account.getString("number");
            boolean expected = number != null && SyscohadaAccounts.ENGINE_ACCOUNTS.contains(number);
            Boolean current = account.getBoolean("system");
            if (current != null && current == expected) continue;
            ops.add(new UpdateOneModel<>(
                    Filters.eq("_id", account.get("_id")),
                    Updates.set("system", expected)));
        }

        if (!ops.isEmpty()) accounts.bulkWrite(ops);
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        // Rien à défaire : reverrouiller tout le plan rendrait de nouveau
        // impossible ce que cette migration vient d'ouvrir.
    }
}
