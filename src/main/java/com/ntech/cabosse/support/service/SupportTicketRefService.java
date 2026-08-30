package com.ntech.cabosse.support.service;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import com.ntech.cabosse.shared.persistence.ControlPlaneProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.time.Year;

/**
 * Numérotation des tickets, de la forme {@code TCK-2026-0001}.
 *
 * <p>Le compteur est <strong>global</strong> et non par structure : c'est
 * l'éditeur qui vit avec ces numéros au quotidien, et deux tickets portant
 * le même numéro chez deux coopératives différentes rendraient toute
 * conversation ambiguë.</p>
 *
 * <p>Même mécanique que les compteurs métier : un incrément atomique sur
 * un document unique, avec création à la volée. Pas de lecture suivie
 * d'une écriture, qui perdrait une course.</p>
 */
@ApplicationScoped
public class SupportTicketRefService {

    private static final String COLLECTION = "counters";
    private static final String KEY_PREFIX = "support_ticket:";

    @Inject ControlPlaneProvider controlPlane;

    public String next() {
        int year = Year.now().getValue();
        Document updated = controlPlane.database().getCollection(COLLECTION)
                .findOneAndUpdate(
                        Filters.eq("_id", KEY_PREFIX + year),
                        Updates.inc("seq", 1L),
                        new FindOneAndUpdateOptions().upsert(true)
                                .returnDocument(ReturnDocument.AFTER));
        long seq = updated != null ? updated.getLong("seq") : 1L;
        return String.format("TCK-%d-%04d", year, seq);
    }
}
