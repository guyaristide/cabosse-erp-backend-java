package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.ntech.cabosse.shared.persistence.ControlPlane;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Migration 084 — la devise quitte les noms de champs persistés.
 *
 * <p>Règle de la maison rappelée le 04/09/2026 : la devise est une
 * préférence du tenant, pas un invariant du produit. Cent quarante-cinq
 * champs écrits en base portaient pourtant le suffixe {@code Fcfa}
 * ({@code amountFcfa}, {@code monthlyPriceFcfa}…). Les sources sont
 * renommées le même jour, en un seul geste : sans cette reprise, un
 * document existant garderait ses anciennes clés, que la désérialisation
 * ignorerait, et tous ses montants se reliraient nuls, sans erreur.</p>
 *
 * <p>La reprise est un parcours complet de chaque collection du tenant
 * avec réécriture des clés, en profondeur (sous-documents et tableaux de
 * lignes compris) : un {@code $rename} ne sait pas traverser un tableau,
 * et l'énumération des chemins par collection serait aussi longue que
 * fausse à la première évolution. Les volumes datent d'avant toute
 * production, le parcours complet est le choix simple et vérifiable.</p>
 *
 * <p>Côté plan de contrôle, trois emplacements portent ces clés : la
 * ligne du tenant (seuils dans {@code preferences}), ses lignes d'audit
 * (charges utiles), et le catalogue des offres ({@code plans}, partagé,
 * repris de façon idempotente : au second passage il n'y a plus rien à
 * renommer).</p>
 *
 * <p>La règle de renommage est le miroir exact de celle appliquée aux
 * sources : suffixe retiré, sauf les cas où le nom nu perdait son sens,
 * listés dans {@link #EXCEPTIONS}.</p>
 */
@ChangeUnit(id = "drop_fcfa_from_field_names", order = "084", author = "neiba")
public class M084_DropFcfaFromFieldNames {

    private static final String SUFFIX = "Fcfa";

    /** Les renommages qui ne suivent pas la règle du suffixe retiré. */
    private static final Map<String, String> EXCEPTIONS = Map.of(
            // La paire Pct/Fcfa perd son sens si l'un des deux devient nu.
            "inventoryAlertThresholdFcfa", "inventoryAlertThresholdAmount",
            "thresholdFcfa", "thresholdAmount",
            // Trop courts une fois nus.
            "inFcfa", "inAmount",
            "outFcfa", "outAmount",
            // Vocabulaire FEC français conservé tel quel.
            "montantFcfa", "montant");

    @Execution
    public void execute(MongoDatabase database, MongoClient client) {
        for (String name : database.listCollectionNames()) {
            rewriteCollection(database.getCollection(name), null);
        }
        rewriteControlPlane(database, client);
    }

    /** Les données du tenant dans le plan de contrôle, plus le catalogue partagé. */
    private void rewriteControlPlane(MongoDatabase database, MongoClient client) {
        MongoDatabase control = client.getDatabase(ControlPlane.DATABASE);
        Document tenant = control.getCollection(ControlPlane.Collections.TENANTS)
                .find(Filters.eq("databaseName", database.getName())).first();
        if (tenant != null) {
            rewriteCollection(control.getCollection(ControlPlane.Collections.TENANTS),
                    Filters.eq("_id", tenant.get("_id")));
            rewriteCollection(control.getCollection(ControlPlane.Collections.GLOBAL_AUDIT),
                    Filters.eq("tenantId", tenant.get("_id")));
        }
        rewriteCollection(control.getCollection(ControlPlane.Collections.PLANS), null);
    }

    private void rewriteCollection(MongoCollection<Document> collection, org.bson.conversions.Bson scope) {
        var cursor = scope == null ? collection.find() : collection.find(scope);
        for (Document doc : cursor) {
            Document renamed = (Document) renameKeys(doc);
            if (!renamed.equals(doc)) {
                collection.replaceOne(Filters.eq("_id", doc.get("_id")), renamed);
            }
        }
    }

    /** Réécrit les clés en profondeur ; rend une copie, jamais l'original. */
    private Object renameKeys(Object value) {
        if (value instanceof Document doc) {
            Document out = new Document();
            for (Map.Entry<String, Object> entry : new LinkedHashMap<>(doc).entrySet()) {
                out.put(newKey(entry.getKey()), renameKeys(entry.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) out.add(renameKeys(item));
            return out;
        }
        return value;
    }

    private String newKey(String key) {
        String mapped = EXCEPTIONS.get(key);
        if (mapped != null) return mapped;
        if (key.endsWith(SUFFIX) && key.length() > SUFFIX.length()) {
            return key.substring(0, key.length() - SUFFIX.length());
        }
        return key;
    }

    /**
     * Pas de retour en arrière : les documents écrits après la migration
     * portent déjà les nouveaux noms, un retour serait partiel, donc faux.
     */
    @RollbackExecution
    public void rollback(MongoDatabase database) {
        // Volontairement vide.
    }
}
