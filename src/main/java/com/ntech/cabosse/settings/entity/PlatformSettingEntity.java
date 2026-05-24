package com.ntech.cabosse.settings.entity;

import com.ntech.cabosse.shared.persistence.ControlPlane;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Paramètres plateforme regroupés par section ({@code email}, {@code storage},
 * {@code payment.wave}, {@code notifications}, etc.). Une entrée par section
 * dans {@code cabosse_control.platform_settings}.
 *
 * <p>Le {@code _id} du document est le nom de la section — un slug stable
 * comme {@code "email"} ou {@code "storage"}. La valeur {@link #values}
 * est une map de clés vers valeurs (toutes typées en String pour rester
 * homogène ; les codes appelants parsent en number/boolean au besoin).</p>
 *
 * <p>Les valeurs sensibles (mots de passe, API keys) sont chiffrées AES-GCM
 * avant écriture — voir {@code SecretCipher} et la liste {@link #secretKeys}
 * qui précise quelles clés sont chiffrées dans cette section. Le service
 * applicatif gère le déchiffrement transparent en lecture.</p>
 */
@MongoEntity(database = ControlPlane.DATABASE, collection = ControlPlane.Collections.PLATFORM_SETTINGS)
public class PlatformSettingEntity extends PanacheMongoEntityBase {

    /** Nom de la section, p.ex. {@code "email"}, {@code "storage"}. */
    @BsonId
    public String section;

    /** Valeurs brutes (toujours en String). Les secrets sont stockés chiffrés. */
    public Map<String, String> values = new HashMap<>();

    /**
     * Liste des clés de {@link #values} chiffrées AES-GCM. Permet au
     * service de savoir quoi déchiffrer en lecture et quoi masquer dans
     * la vue admin.
     */
    public java.util.Set<String> secretKeys = new java.util.HashSet<>();

    public Instant updatedAt;

    /** Email du super-admin auteur de la dernière mise à jour. */
    public String updatedBy;
}
