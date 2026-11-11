package com.ntech.cabosse.user.entity;

import com.ntech.cabosse.shared.persistence.ControlPlane;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Appartenance d'un compte à une structure (SAAS-29). Vit dans le plan
 * contrôle ({@code cabosse_control.memberships}), un document par couple
 * (utilisateur, tenant).
 *
 * <p>Un compte est une identité : adresse, nom, mot de passe, langue. Ce
 * qu'il peut faire dans une structure donnée vit ici : rôle, profils,
 * sites de travail, exceptions de droits, et la porte ouverte ou fermée.
 * Une même équipe qui travaille pour deux entreprises d'un même groupe a
 * donc un compte par personne et une appartenance par entreprise, et
 * passe de l'une à l'autre sans se reconnecter.</p>
 *
 * <p>Le compte garde sa structure d'origine dans {@code UserEntity.tenantId} :
 * c'est celle que la connexion propose à défaut d'une dernière structure
 * visitée, et celle dont l'appartenance est sous-entendue tant que ce
 * document n'existe pas (comptes d'avant la mise à jour, archives
 * restaurées).</p>
 */
@MongoEntity(database = ControlPlane.DATABASE, collection = ControlPlane.Collections.MEMBERSHIPS)
public class MembershipEntity extends PanacheMongoEntityBase {

    @BsonId
    public UUID id;

    public UUID userId;
    public UUID tenantId;

    /**
     * Rôle dans cette structure : {@code TENANT_ADMIN} ou {@code USER}.
     * Le rôle de plateforme n'y figure jamais, il est porté par le compte.
     */
    public Set<String> roles = new HashSet<>();

    /** Profils de la structure rattachés (backlog ADM-01). */
    public List<UUID> tenantRoleIds = new ArrayList<>();

    /** Sites de travail dans cette structure (backlog ADM-02). Vide : tous. */
    public List<UUID> allowedSiteIds = new ArrayList<>();

    /** Droits accordés ou retirés à cette personne seule, ici (backlog ADM-03). */
    public List<UserPermissionException> permissionExceptions = new ArrayList<>();

    public MembershipStatus status = MembershipStatus.ACTIVE;

    /** Qui a ouvert la porte, pour le journal. */
    public String invitedByEmail;

    public Instant createdAt;
    public Instant updatedAt;

    /** Dernière entrée dans cette structure : la connexion y revient d'office. */
    public Instant lastUsedAt;

    public MembershipEntity() {}

    public boolean isActive() {
        return status == null || status == MembershipStatus.ACTIVE;
    }

    public boolean isTenantAdmin() {
        return roles != null && roles.contains(com.ntech.cabosse.shared.security.Roles.TENANT_ADMIN);
    }
}
