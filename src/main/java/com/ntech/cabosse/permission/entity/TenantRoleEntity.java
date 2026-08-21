package com.ntech.cabosse.permission.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Profil composé par l'administrateur du tenant (backlog ADM-01).
 *
 * <p>Aucune organisation ne ressemble à une autre : là où une coopérative
 * sépare magasinier, comptable et directeur, une petite structure confie
 * tout à deux personnes. Plutôt que d'imposer des profils, la plateforme
 * fournit des permissions et laisse l'administrateur les assembler.</p>
 *
 * <p>Un seul profil est commun à tous les tenants et n'est pas stocké ici :
 * l'administrateur du tenant, qui détient d'office toutes les permissions
 * que ses capacités rendent applicables.</p>
 */
public class TenantRoleEntity {

    @BsonId
    public UUID id;

    /** Code stable en majuscules, saisi ou dérivé du nom. */
    public String code;

    public String name;
    public String description;

    /** Codes de {@link Permission}. Les codes inconnus sont ignorés à la lecture. */
    public List<String> permissions = new ArrayList<>();

    public boolean active = true;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
}
