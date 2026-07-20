package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * Migration 040 — normalisation des numéros de compte au format 6 caractères
 * SYSCOHADA/AUDCIF (backlog CPT-16). L'expert-comptable travaille en comptes
 * à 6 chiffres ({@code 601000}, {@code 445660}…) ; le seed historique et les
 * pièces déjà émises portaient des codes courts ({@code 601}, {@code 44566}).
 *
 * <p>Règle de conversion : tout numéro purement numérique de 2 à 5 chiffres
 * est complété à droite par des zéros jusqu'à 6 caractères ({@code 401 ->
 * 401000}, {@code 4456 -> 445600}, {@code 44566 -> 445660}). Les codes déjà
 * à 6 chiffres et les valeurs non numériques sont laissés intacts, ce qui
 * rend la migration idempotente.</p>
 *
 * <p>Le premier (et le second) caractère étant préservé, toute la
 * classification par préfixe du moteur (classes 6/7/8, familles à 2 chiffres,
 * regroupements de la balance et du grand-livre) reste valide.</p>
 *
 * <p>Collections reprises : plan comptable, comptes bancaires, compte de
 * charge par article, compte par type de dépense, et les écritures embarquées
 * des pièces du journal et des brouillons d'OD.</p>
 */
@ChangeUnit(id = "normalize_six_digit_accounts", order = "040", author = "neiba")
public class M040_NormalizeSixDigitAccounts {

    /** Complète à droite un code numérique court (2-5 chiffres) jusqu'à 6. */
    static String pad6(String account) {
        if (account == null) return null;
        if (!account.matches("\\d{2,5}")) return account;
        return account + "0".repeat(6 - account.length());
    }

    @Execution
    public void execute(MongoDatabase database) {
        padScalarField(database.getCollection("chart_of_accounts"), "number");
        padScalarField(database.getCollection("bank_accounts"), "syscohadaAccount");
        padScalarField(database.getCollection("articles"), "purchaseChargeAccount");
        padScalarField(database.getCollection("expense_types"), "syscohadaAccount");
        padEmbeddedEntries(database.getCollection("journal_pieces"));
        padEmbeddedEntries(database.getCollection("od_drafts"));
    }

    /** Reprise d'un champ compte scalaire : un {@code updateOne} par document modifié. */
    private void padScalarField(MongoCollection<Document> collection, String field) {
        for (Document doc : collection.find(Filters.exists(field))) {
            String current = doc.getString(field);
            String padded = pad6(current);
            if (padded != null && !padded.equals(current)) {
                collection.updateOne(Filters.eq("_id", doc.get("_id")),
                        Updates.set(field, padded));
            }
        }
    }

    /** Reprise du tableau {@code entries[].syscohadaAccount} embarqué. */
    private void padEmbeddedEntries(MongoCollection<Document> collection) {
        for (Document doc : collection.find(Filters.exists("entries"))) {
            List<Document> entries = doc.getList("entries", Document.class);
            if (entries == null) continue;
            boolean changed = false;
            List<Document> rebuilt = new ArrayList<>(entries.size());
            for (Document entry : entries) {
                String current = entry.getString("syscohadaAccount");
                String padded = pad6(current);
                if (padded != null && !padded.equals(current)) {
                    entry.put("syscohadaAccount", padded);
                    changed = true;
                }
                rebuilt.add(entry);
            }
            if (changed) {
                collection.updateOne(Filters.eq("_id", doc.get("_id")),
                        Updates.set("entries", rebuilt));
            }
        }
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        // Reprise idempotente et sans perte de données : rien à défaire.
    }
}
