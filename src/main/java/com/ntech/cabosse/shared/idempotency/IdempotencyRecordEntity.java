package com.ntech.cabosse.shared.idempotency;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;

/**
 * Trace d'une opération d'écriture déjà traitée, dans la base du tenant.
 *
 * <p>Sans elle, un terminal qui perd le réseau juste après l'envoi ne peut
 * pas savoir si son opération est passée. Rejouer crée un doublon, ne pas
 * rejouer perd la saisie. Avec elle, le rejeu <strong>renvoie la réponse
 * d'origine</strong> : l'appelant obtient le même résultat qu'au premier
 * coup, référence comprise, comme si le réseau n'avait jamais coupé.</p>
 *
 * <p>L'empreinte du contenu est conservée pour détecter la clé réutilisée
 * pour autre chose : renvoyer la réponse d'une opération à la place d'une
 * autre serait pire que tout.</p>
 */
public class IdempotencyRecordEntity {

    /** La clé d'idempotence elle-même, fournie par le client. */
    @BsonId
    public String key;

    /** Méthode et chemin, pour qu'une clé ne traverse pas deux endpoints. */
    public String method;
    public String path;

    /** Empreinte du corps de la requête d'origine. */
    public String payloadHash;

    /** Statut HTTP rendu la première fois. */
    public int statusCode;

    /** Corps de la réponse d'origine, rejoué tel quel. */
    public String responseBody;

    /** Utilisateur à l'origine de la première exécution. */
    public String actorEmail;

    public Instant createdAt;

    /**
     * Date de péremption de la trace. Passé ce délai, la même clé sera
     * traitée comme neuve : au-delà de quelques semaines, un terminal qui
     * rejoue une opération si ancienne a un problème plus grave qu'un
     * doublon.
     */
    public Instant expiresAt;

    public IdempotencyRecordEntity() {}
}
