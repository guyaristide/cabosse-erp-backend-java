package com.ntech.cabosse.members.service;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import com.ntech.cabosse.members.repository.MemberRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.i18n.Messages;
import com.ntech.cabosse.shared.persistence.TenantMongoDatabaseProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.time.Year;

/**
 * Génère les références séquentielles {@code MB-YYYY-NNNN} pour les
 * membres.
 *
 * <p>Le compteur ne connaît que ce qu'il a lui-même distribué. Un code
 * posé autrement, par un import qui apporte les siens ou par une saisie
 * à la main, lui reste invisible : il le redistribue ensuite et la
 * création est refusée en doublon. Neuf producteurs à ouvrir au passage
 * d'une comptabilisation échouaient ainsi tous les neuf, et le bordereau
 * entier restait à comptabiliser (constaté en production le
 * 06/10/2026).</p>
 *
 * <p>Il avance donc jusqu'à un code réellement libre. Chaque tentative
 * consomme un numéro de façon atomique : deux créations simultanées ne
 * peuvent pas obtenir le même.</p>
 */
@ApplicationScoped
public class MemberRefService {

    private static final String COLLECTION = "counters";
    private static final String KEY_PREFIX = "member:";

    /**
     * Au-delà, on cesse de chercher.
     *
     * <p>Un référentiel où mille codes consécutifs sont pris n'est pas un
     * retard de compteur : c'est une anomalie, et tourner sans fin la
     * masquerait.</p>
     */
    private static final int MAX_ATTEMPTS = 1000;

    @Inject TenantMongoDatabaseProvider tenantDb;
    @Inject MemberRepository members;

    public String next() {
        MongoCollection<Document> coll = tenantDb.database().getCollection(COLLECTION);
        int year = Year.now().getValue();
        String key = KEY_PREFIX + year;
        String code = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Document updated = coll.findOneAndUpdate(
                    Filters.eq("_id", key),
                    Updates.inc("seq", 1L),
                    new FindOneAndUpdateOptions()
                            .upsert(true)
                            .returnDocument(ReturnDocument.AFTER)
            );
            long seq = updated != null ? updated.getLong("seq") : 1L;
            code = String.format("MB-%d-%04d", year, seq);
            if (!members.codeExists(code)) return code;
        }
        throw new BusinessException(Messages.msg("m.mbr-code-sequence-exhausted", code));
    }
}
