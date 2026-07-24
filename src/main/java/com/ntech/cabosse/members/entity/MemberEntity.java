package com.ntech.cabosse.members.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Membre-producteur d'une structure organisée (coopérative, GIE,
 * association). Tenant-scopé (collection {@code members}).
 *
 * <p>Disponible si la capacité {@link com.ntech.cabosse.tenant.capability.TenantCapability#HAS_MEMBERS}
 * est active (i.e. {@code organizationModel ∈ {COOPERATIVE,
 * INFORMAL_GROUP}}). Indépendant de la filière : utilisable par toute
 * structure organisée, quelle que soit la matière travaillée.</p>
 *
 * <p><strong>Lien Supplier miroir</strong> : à la création d'un membre,
 * un {@code SupplierEntity} est auto-créé et lié via {@link #supplierId}.
 * Les flux d'achats (BC, RD), de comptabilité (compte 401) et de stock
 * fonctionnent sans modification — le membre est un fournisseur
 * particulier avec attributs étendus. Le compte fournisseur miroir est
 * marqué pour identifier ce lien.</p>
 */
public class MemberEntity {

    @BsonId
    public UUID id;

    /** Référence affichable {@code MB-YYYY-NNNN}. Unique par tenant. */
    public String code;

    /**
     * Nom complet recomposé ({@code lastName + " " + firstName}) tel qu'il
     * sera communiqué dans les bordereaux et la rémunération. Pour une
     * personne morale, raison sociale. <strong>Maintenu par le service</strong>
     * à partir de {@link #firstName} / {@link #lastName} (backlog MEM-06) —
     * conservé pour les lecteurs existants (fournisseur miroir, exports, audit).
     */
    public String name;

    /** Prénoms du producteur (backlog MEM-06). Facultatif. */
    public String firstName;

    /** Nom du producteur (backlog MEM-06). Pour une personne morale, raison sociale. */
    public String lastName;

    public MemberCivilStatus civilStatus = MemberCivilStatus.UNKNOWN;

    /**
     * Date de naissance complète (backlog MEM-06). Facultative. Exclusive de
     * {@link #birthYear} : si seule l'année est connue, {@code birthDate} est
     * null et {@code birthYear} renseigné.
     */
    public LocalDate birthDate;

    /** Année de naissance seule, quand la date complète est inconnue (MEM-06). */
    public Integer birthYear;

    /**
     * Type de pièce d'identité (libellé dénormalisé, choisi dans le référentiel
     * des types de pièces, backlog MEM-06). Facultatif.
     */
    public String idDocType;

    /** Numéro de la pièce d'identité (backlog MEM-06). Facultatif. */
    public String idDocNumber;

    /** Référence vers {@code CloudFileEntity.id} pour la pièce d'identité scannée. */
    public UUID idCardFileId;

    /**
     * Section de rattachement (backlog MEM-06). FK vers
     * {@code SectionEntity.id} (référentiel des sections de collecte).
     */
    public UUID sectionId;

    /**
     * Agent de suivi du producteur (backlog MEM-06). Référence vers un autre
     * {@link MemberEntity} (l'agent est lui-même un membre) ; pas de table
     * dédiée. Nom et contact viennent de la fiche de l'agent.
     */
    public UUID followUpAgentMemberId;

    /**
     * Articles livrés à la coopérative (backlog MEM-06). Références vers des
     * articles marqués achetables. Facultatif, multiple.
     */
    public List<UUID> deliveredArticleIds = new ArrayList<>();

    /** Identifiants externes du producteur (backlog MEM-06). Cumulables. */
    public List<MemberExternalCode> externalProducerCodes = new ArrayList<>();

    /** Village / localité d'origine. Saisie libre. */
    public String village;

    /** Téléphone principal (format libre, idéalement international). */
    public String phone;

    public String email;

    /** Date d'adhésion à la structure. */
    public LocalDate joinedAt;

    /** Capital social ou parts sociales souscrites (en devise tenant). */
    public BigDecimal partsSocialesAmount;

    public MemberStatus status = MemberStatus.ACTIVE;

    /**
     * UUID du {@code SupplierEntity} miroir auto-créé pour ce membre.
     * Sert aux flux d'achat (BC fèves, RD) qui référencent un supplier.
     */
    public UUID supplierId;

    /**
     * UUIDs des parcelles rattachées au membre. La parcelle porte aussi
     * le {@code memberId} en miroir pour les requêtes inverses. La
     * cohérence est maintenue par {@code MemberService} et
     * {@code ParcelService}.
     */
    public List<UUID> parcels = new ArrayList<>();

    /**
     * Mode de paiement préféré pour la rémunération. Texte libre au MVP
     * ({@code "Mobile Money - Orange"}, {@code "Cash"},
     * {@code "Virement SGBCI"}). À typer plus finement en Phase 7
     * quand l'intégration mobile money sera faite.
     */
    public String preferredPaymentMethod;

    /** Numéro mobile money pour réception des paiements (si applicable). */
    public String mobileMoneyNumber;

    public String notes;

    /** Motif du dernier rejet / de la dernière suspension / de la radiation. */
    public String statusReason;

    /**
     * Pièces du dossier d'adhésion (attestation d'exploitation, contrat,
     * justificatifs…). Le binaire vit dans {@code cloud_files} ; la fiche
     * ne porte que les métadonnées. La pièce d'identité historique reste
     * sur {@link #idCardFileId}.
     */
    public List<Document> documents = new ArrayList<>();

    public static class Document {
        public UUID id;
        /** Libellé métier de la pièce (« Attestation d'exploitation »…). */
        public String label;
        public String fileName;
        public String mimeType;
        public long sizeBytes;
        /** Référence du binaire dans {@code cloud_files}. */
        public UUID cloudFileId;
        public Instant uploadedAt;
    }

    /** Validation du dossier d'adhésion. */
    public Instant approvedAt;
    public UUID approvedBy;

    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
    public String createdByEmail;

    public MemberEntity() {}
}
