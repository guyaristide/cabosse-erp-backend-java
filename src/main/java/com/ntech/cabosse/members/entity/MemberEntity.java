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

    /**
     * <strong>Legacy</strong> — mélangeait genre et nature juridique. Reste
     * maintenu <em>par le service</em> à partir de {@link #gender} et
     * {@link #personType} pour les lecteurs existants (registre producteurs,
     * exports, indicateurs). Ne plus lire ce champ dans du code neuf.
     */
    public MemberCivilStatus civilStatus = MemberCivilStatus.UNKNOWN;

    /** Genre du producteur (backlog MEM-07). */
    public MemberGender gender = MemberGender.UNKNOWN;

    /** Nature juridique (backlog MEM-07). Personne physique par défaut. */
    public MemberPersonType personType = MemberPersonType.NATURAL_PERSON;

    /** Situation matrimoniale (backlog MEM-07). */
    public MemberMaritalStatus maritalStatus = MemberMaritalStatus.UNKNOWN;

    /** Lieu de naissance déclaré (backlog MEM-07). Saisie libre. */
    public String birthPlace;

    /**
     * Volet personne morale (backlog MEM-07). Null pour une personne
     * physique.
     */
    public MemberLegalIdentity legalIdentity;

    /**
     * Date de naissance complète (backlog MEM-06). Facultative. Exclusive de
     * {@link #birthYear} : si seule l'année est connue, {@code birthDate} est
     * null et {@code birthYear} renseigné.
     */
    public LocalDate birthDate;

    /** Année de naissance seule, quand la date complète est inconnue (MEM-06). */
    public Integer birthYear;

    /**
     * Type de la <strong>première</strong> pièce d'identité (libellé
     * dénormalisé, backlog MEM-06). Maintenu par le service depuis
     * {@link #identityDocuments} pour les lecteurs existants (registre,
     * exports). Ne plus écrire ce champ directement.
     */
    public String idDocType;

    /** Numéro de la première pièce d'identité. Voir {@link #idDocType}. */
    public String idDocNumber;

    /** Scan de la première pièce d'identité. Voir {@link #idDocType}. */
    public UUID idCardFileId;

    /**
     * Pièces d'identité du producteur (backlog MEM-07). Une carte d'identité,
     * un identifiant national, une carte consulaire… cumulables.
     */
    public List<MemberIdentityDocument> identityDocuments = new ArrayList<>();

    /** Ménage du producteur (backlog MEM-08). */
    public MemberHousehold household = new MemberHousehold();

    /** Recensement et fraîcheur du dossier (backlog MEM-09). */
    public MemberEnrolment enrolment = new MemberEnrolment();

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

    /**
     * Identifiants externes du producteur. <strong>Remplacés par les pièces
     * dont le type est coché « sert d'identifiant »</strong> (migration
     * M052) : un numéro de carte est une pièce, avec un émetteur, une
     * validité et une photocopie. Conservé en lecture pour les fiches non
     * encore rouvertes, alimenté par le service depuis les pièces.
     */
    @Deprecated
    public List<MemberExternalCode> externalProducerCodes = new ArrayList<>();

    /**
     * Le producteur est aussi <strong>délégué collecteur</strong> : il
     * reçoit des avances de fonds et achète aux autres producteurs pour le
     * compte de la structure.
     *
     * <p>La qualité de délégué se porte techniquement sur le fournisseur
     * miroir, seul objet que connaissent les avances. Ce drapeau existe
     * pour que la déclaration se fasse depuis la fiche du producteur, là où
     * le gérant la pense, plutôt qu'en allant cocher une case sur une fiche
     * fournisseur créée automatiquement dont personne ne soupçonne
     * l'existence. Les deux restent synchronisés dans les deux sens.</p>
     */
    public boolean collector = false;

    /**
     * Rémunération propre à ce délégué, dans l'unité du mode retenu par le
     * tenant. {@code null} : le taux commun s'applique. Recopiée sur le
     * fournisseur miroir, qui la porte pour les reçus d'achat.
     */
    public java.math.BigDecimal collectorMarginRate;

    /**
     * Rémunération convenue campagne par campagne. Recopiée sur le
     * fournisseur miroir, qui la porte pour les reçus d'achat. Voir
     * {@link com.ntech.cabosse.supplier.entity.SupplierEntity#collectorMarginByCampaign}.
     */
    public java.util.List<com.ntech.cabosse.supplier.entity.SupplierEntity.CampaignMargin>
            collectorMarginByCampaign = new java.util.ArrayList<>();

    /**
     * Numéros normalisés par lesquels ce producteur peut être retrouvé dans
     * un fichier importé, dérivés des pièces dont le type sert
     * d'identifiant. Un index unique porte sur cette liste : deux
     * producteurs ne peuvent pas revendiquer la même carte, faute de quoi
     * un achat serait payé à la mauvaise personne sans que rien ne le
     * signale.
     */
    public List<String> producerRefKeys = new ArrayList<>();

    /**
     * Nom du village d'origine, tel qu'il s'affiche.
     *
     * <p>Dénormalisé depuis {@link #localityId} quand celui-ci est connu.
     * Reste renseigné seul pour les fiches d'avant le rattachement, et pour
     * un village qu'aucune structure n'a encore inscrit à son
     * référentiel.</p>
     */
    public String village;

    /**
     * Localité du référentiel où vit le producteur.
     *
     * <p>C'est ce lien qui permet de savoir quel délégué collecte chez lui :
     * une localité est gérée par un seul délégué. Tant que le village n'est
     * qu'une chaîne saisie, le rattachement producteur → délégué doit être
     * ressaisi à la main, avec une occasion de plus de se contredire.</p>
     *
     * <p>Null tant que le rapprochement n'a pas été fait : il ne s'invente
     * pas, deux villages homonymes existant dans deux sections.</p>
     */
    public UUID localityId;

    /** Téléphone principal (format libre, idéalement international). */
    public String phone;

    public String email;

    /** Date d'adhésion à la structure. */
    public LocalDate joinedAt;

    /** Capital social ou parts sociales souscrites (en devise tenant). */
    public BigDecimal partsSocialesAmount;


    /**
     * Compte comptable d'avance propre à ce tiers.
     *
     * <p>Demandé par l'expert le 03/09/2026 : « le numéro de compte
     * comptable d'avance spécifique du producteur déjà créé dans le plan
     * comptable ». La coopérative ouvre le compte dans son plan, nous le
     * rattachons ; il n'y a ni masque à inventer ni numéro à générer.</p>
     *
     * <p>Nul quand rien n'est rattaché : l'écriture retombe alors sur le
     * compte collectif du tenant. Une avance ne se bloque pas parce qu'une
     * fiche est incomplète.</p>
     */
    public String advanceAccount;

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

    /**
     * Titulaire déclaré du compte mobile money (backlog MEM-12). Vide, on
     * suppose le membre lui-même. Renseigné et différent du membre, le
     * paiement passe par un tiers : c'est là que se glissent les
     * détournements, d'où le mandat écrit exigé ci-dessous.
     */
    public String mobileMoneyHolderName;

    /** Mandat écrit au dossier autorisant le paiement à un tiers (MEM-12). */
    public boolean mobileMoneyMandateOnFile;

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
