package com.ntech.cabosse.migrations;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.jboss.logging.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Migration 059 — remet en Decimal128 les montants stockés dans un autre
 * type numérique.
 *
 * <p>Un champ {@code BigDecimal} du modèle se lit en Decimal128 et rien
 * d'autre. Une migration qui pose un {@code 0} entier, un script
 * d'exploitation ou un pipeline dont les replis sont entiers écrivent un
 * Int32, et le pilote refuse alors de décoder. L'échec ne porte pas sur la
 * ligne fautive mais sur la requête entière : un seul document mal typé
 * fait tomber la liste complète.</p>
 *
 * <p>C'est arrivé avec {@code M051}, qui posait {@code delegateMargin}
 * à zéro entier sur les reçus antérieurs au paramétrage de la
 * rémunération. Plutôt que de corriger ce seul champ, on balaie les
 * collections du domaine financier : les champs à convertir sont
 * <strong>déduits des entités elles-mêmes</strong>, si bien qu'un montant
 * ajouté au modèle plus tard sera couvert sans qu'on y pense.</p>
 */
@ChangeUnit(id = "normalize_decimal_amounts", order = "059", author = "neiba")
public class M059_NormalizeDecimalAmounts {

    private static final Logger LOG = Logger.getLogger(M059_NormalizeDecimalAmounts.class);

    /** Types BSON numériques qui ne sont pas des Decimal128. */
    private static final List<String> NUMERIC = List.of("int", "long", "double");

    /** Collections du domaine financier, avec l'entité qui les décrit. */
    private static final Map<String, Class<?>> TARGETS = Map.of(
            "producer_purchases", com.ntech.cabosse.producerpurchase.entity.ProducerPurchaseEntity.class,
            "producer_payments", com.ntech.cabosse.producerpayment.entity.ProducerPaymentEntity.class,
            "collector_advances", com.ntech.cabosse.collector.entity.CollectorAdvanceEntity.class,
            "member_credits", com.ntech.cabosse.membercredit.entity.MemberCreditEntity.class,
            "journal_pieces", com.ntech.cabosse.accounting.entity.JournalPieceEntity.class
    );

    @Execution
    public void execute(MongoDatabase database) {
        for (Map.Entry<String, Class<?>> target : TARGETS.entrySet()) {
            normalize(database, target.getKey(), target.getValue());
        }
    }

    private void normalize(MongoDatabase database, String collectionName, Class<?> entity) {
        Set<String> roots = new LinkedHashSet<>();
        Map<String, Set<String>> nested = new LinkedHashMap<>();
        discover(entity, roots, nested);
        if (roots.isEmpty() && nested.isEmpty()) return;

        List<Bson> anyMistyped = new ArrayList<>();
        for (String field : roots) anyMistyped.add(Filters.type(field, "int"));
        for (String field : roots) anyMistyped.add(Filters.type(field, "long"));
        for (String field : roots) anyMistyped.add(Filters.type(field, "double"));
        for (Map.Entry<String, Set<String>> arr : nested.entrySet()) {
            for (String sub : arr.getValue()) {
                String path = arr.getKey() + "." + sub;
                anyMistyped.add(Filters.type(path, "int"));
                anyMistyped.add(Filters.type(path, "long"));
                anyMistyped.add(Filters.type(path, "double"));
            }
        }

        var collection = database.getCollection(collectionName);
        Bson selector = Filters.or(anyMistyped);
        long concerned = collection.countDocuments(selector);
        if (concerned == 0) return;

        Document set = new Document();
        for (String field : roots) set.append(field, convert("$" + field));
        for (Map.Entry<String, Set<String>> arr : nested.entrySet()) {
            set.append(arr.getKey(), mapArray(arr.getKey(), arr.getValue()));
        }

        var result = collection.updateMany(selector, List.of(new Document("$set", set)));
        LOG.infof("M059 : %s documents normalisés dans %s (%d concernés)",
                result.getModifiedCount(), collectionName, concerned);
    }

    /**
     * Champs {@code BigDecimal} de l'entité, à la racine et dans ses
     * listes d'objets imbriqués (lignes d'écriture, imputations,
     * affectations d'un règlement).
     */
    private static void discover(Class<?> entity, Set<String> roots, Map<String, Set<String>> nested) {
        for (Field field : entity.getFields()) {
            if (field.getType() == BigDecimal.class) {
                roots.add(field.getName());
                continue;
            }
            if (!List.class.isAssignableFrom(field.getType())) continue;
            if (!(field.getGenericType() instanceof ParameterizedType parameterized)) continue;
            if (!(parameterized.getActualTypeArguments()[0] instanceof Class<?> item)) continue;
            for (Field sub : item.getFields()) {
                if (sub.getType() != BigDecimal.class) continue;
                nested.computeIfAbsent(field.getName(), k -> new LinkedHashSet<>()).add(sub.getName());
            }
        }
    }

    /** Convertit une valeur numérique en décimal, laisse le reste intact. */
    private static Document convert(String path) {
        return new Document("$cond", List.of(
                new Document("$in", List.of(new Document("$type", path), NUMERIC)),
                new Document("$toDecimal", path),
                path));
    }

    /**
     * Réécrit les éléments d'un tableau en convertissant leurs montants.
     * Un tableau absent le reste : le recréer vide ferait perdre la
     * distinction entre « pas de lignes » et « champ jamais renseigné ».
     */
    private static Document mapArray(String arrayField, Set<String> subFields) {
        Document merged = new Document();
        for (String sub : subFields) merged.append(sub, convert("$$item." + sub));
        Document map = new Document("$map", new Document()
                .append("input", "$" + arrayField)
                .append("as", "item")
                .append("in", new Document("$mergeObjects", List.of("$$item", merged))));
        return new Document("$cond", List.of(
                new Document("$eq", List.of(new Document("$type", "$" + arrayField), "array")),
                map,
                "$" + arrayField));
    }

    @RollbackExecution
    public void rollback(MongoDatabase database) {
        // Restaurer un typage erroné n'aurait aucun sens.
    }
}
