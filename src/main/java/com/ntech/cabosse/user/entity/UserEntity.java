package com.ntech.cabosse.user.entity;

import com.ntech.cabosse.shared.persistence.ControlPlane;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Utilisateur authentifiable dans Cabosse ERP. Vit dans le plan contrôle
 * ({@code cabosse_control.users}).
 *
 * <p>Un utilisateur appartient à <strong>exactement un tenant</strong>,
 * porté par {@link #tenantId}. Cas particulier : les super-admins
 * plateforme (rôle {@code PLATFORM_ADMIN}) sont rattachés à un tenant
 * technique "NEIBA Internal" ou laissent {@code tenantId = null}, à
 * arbitrer côté politique de comptes.</p>
 *
 * <p>Le mot de passe est stocké hashé (BCrypt), jamais en clair. Le hash
 * n'est <strong>jamais</strong> renvoyé dans une API ({@code UserResponseDto}
 * doit l'omettre explicitement).</p>
 */
@MongoEntity(database = ControlPlane.DATABASE, collection = ControlPlane.Collections.USERS)
public class UserEntity extends PanacheMongoEntityBase {

    @BsonId
    public UUID id;

    public String email;
    public String firstName;
    public String lastName;
    /** Téléphone international ({@code +225 XX XX XX XX XX}). Optionnel. */
    public String phone;

    /**
     * Préférence de langue : {@code "fr"} ou {@code "en"}. {@code null}
     * = pas de choix explicite, le front retombe sur sa langue par défaut.
     */
    public String locale;

    /** Hash BCrypt du mot de passe. Jamais sérialisé en JSON. */
    public String passwordHash;

    /** Tenant de rattachement. Null pour les super-admins plateforme. */
    public UUID tenantId;

    /**
     * Ensemble des rôles du user. Valeurs depuis
     * {@link com.ntech.cabosse.shared.security.Roles}. Vérifié au
     * démarrage par {@code ConstantsConsistencyCheck} (Phase B+).
     */
    public Set<String> roles = new HashSet<>();

    /**
     * Profils du tenant rattachés à cet utilisateur (backlog ADM-01).
     * Vide pour un administrateur, qui détient tout d'office ; vide aussi
     * pour un utilisateur à qui rien n'a encore été confié, qui n'a alors
     * accès à rien.
     */
    public java.util.List<UUID> tenantRoleIds = new java.util.ArrayList<>();

    public UserStatus status;

    /**
     * Hash SHA-256 du token d'invitation. Renseigné quand le user est créé
     * en statut {@code INVITED}, effacé au passage en {@code ACTIVE}.
     */
    public String invitationTokenHash;

    /** Date d'expiration de l'invitation (typiquement +7 jours après création). */
    public Instant invitationExpiresAt;

    public Instant createdAt;
    public Instant updatedAt;
    public Instant lastLoginAt;

    public UserEntity() {}
}
