package com.ntech.cabosse.cacao.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Vente de cacao en gros / export (backlog NEG-02). Tenant-scopé (collection
 * {@code cacao_sales}). Modèle du fichier « DONNEES DE VENTES DE CACAO » :
 * logistique, chaîne de poids (déclaré / déchargé / accepté), réfactions par
 * type, grille qualité, primes de label. Flux distinct des ventes de produits
 * finis.
 *
 * <p>Sortie de stock = {@link Weights#declaredKg} (poids départ) au CMUP.
 * CA facturé = {@code acceptedKg × pricePerKgFcfa} + primes. Marge =
 * {@code amountInvoicedFcfa − (declaredKg × cmupAtSaleFcfa)} : les pertes
 * (écarts de poids, réfactions) tombent dans le coût des ventes.</p>
 */
public class CacaoSaleEntity {

    @BsonId
    public UUID id;

    /** Référence affichable {@code VC-YYYY-NNNN}. Unique par tenant. */
    public String ref;

    public LocalDate date;

    // ─── Contexte ───
    public UUID campaignId;
    public Integer campaignYear;
    /** « Principale » ou « Intermédiaire ». */
    public String campaignType;

    public UUID customerId;
    public String customerName;

    /** Contrat de vente ayant pré-rempli prix/primes (optionnel). */
    public UUID contractId;

    // ─── Article vendu (matière première négociée / produit fini) ───
    public UUID articleId;
    public String articleCode;
    public String articleName;
    public String articleUnit;

    /** Site (magasin) de départ / sortie de stock. */
    public UUID siteId;

    // ─── Blocs ───
    public Logistics logistics = new Logistics();
    public Weights weights = new Weights();
    public Refactions refactions = new Refactions();
    public Quality quality = new Quality();

    // ─── Prix & primes ───
    /** Prix de vente unitaire (prix bord champ campagne + marge), FCFA/kg. */
    public BigDecimal pricePerKgFcfa = BigDecimal.ZERO;
    /** CA commercial = acceptedKg × pricePerKgFcfa. */
    public BigDecimal commercialFcfa = BigDecimal.ZERO;

    public BigDecimal coopPrimeFcfa = BigDecimal.ZERO;
    public BigDecimal producerPrimeFcfa = BigDecimal.ZERO;
    public BigDecimal socialPrimeFcfa = BigDecimal.ZERO;
    public BigDecimal totalPrimeFcfa = BigDecimal.ZERO;

    /** Total facturé au client HT = commercial + primes (MVP : primes sur facture). */
    public BigDecimal amountInvoicedHtFcfa = BigDecimal.ZERO;
    public BigDecimal vatRatePct = BigDecimal.ZERO;
    public BigDecimal vatFcfa = BigDecimal.ZERO;
    public BigDecimal amountInvoicedTtcFcfa = BigDecimal.ZERO;

    // ─── Coût des ventes & marge (dérivés du CMUP) ───
    public BigDecimal cmupAtSaleFcfa = BigDecimal.ZERO;
    public BigDecimal cogsFcfa = BigDecimal.ZERO;
    public BigDecimal marginFcfa = BigDecimal.ZERO;

    // ─── Traces ───
    public String movementRef;
    public String pieceRef;

    // ─── Audit ───
    public Instant createdAt;
    public Instant updatedAt;
    public UUID createdBy;
    public String createdByEmail;
    /**
     * Compteur d'écritures. <strong>Ce n'est pas un verrou</strong> : aucune
     * mise à jour ne le vérifie. La concurrence est traitée autrement sur
     * cette entité (la vente n'est pas modifiée après création). Ne pas s'y fier pour détecter une écriture
     * concurrente.
     */
    public long version = 0L;

    public CacaoSaleEntity() {}

    /** Logistique d'expédition. */
    public static class Logistics {
        public String departureLocation;
        public String destination;
        public String connaissementRef;
        /** Label de certification (RA, FT…) de l'expédition. */
        public String label;
        /** Sections d'origine du cacao (texte). */
        public String originSections;
        public Logistics() {}
    }

    /** Chaîne de poids et sacs. */
    public static class Weights {
        /** Poids déclaré au départ (chargement) = sortie de stock. */
        public BigDecimal declaredKg;
        /** Poids déchargé à l'usine (pesée client). */
        public BigDecimal dischargedKg;
        /** Poids accepté après réfaction usine = base de facturation. */
        public BigDecimal acceptedKg;
        public Integer sacsAccepted;
        public Integer sacsMissing;
        public Integer sacsRejected;
        public Weights() {}
    }

    /** Réfactions usine, en kg par type de défaut. */
    public static class Refactions {
        public BigDecimal usineKg;
        public BigDecimal humidityKg;
        public BigDecimal foreignMatterKg;
        public BigDecimal moldyKg;
        public BigDecimal crabotsKg;
        public BigDecimal brokenKg;
        public BigDecimal wasteKg;
        public BigDecimal otherKg;
        public Refactions() {}
    }

    /** Grille d'analyse qualité (% sauf grade/goût/résultat). */
    public static class Quality {
        public BigDecimal grainage;
        public BigDecimal moldyPct;
        public BigDecimal slatePct;
        public BigDecimal purplePct;
        public BigDecimal mitedPct;
        public BigDecimal flatPct;
        public BigDecimal germinatedPct;
        public BigDecimal defectivePct;
        public BigDecimal foreignMatterPct;
        public BigDecimal ffaPct;
        public BigDecimal brokenPct;
        public BigDecimal humidityPct;
        public String taste;
        /** Grade : G1, G2 (jamais sous-grade à l'export). */
        public String grade;
        /** Résultat d'analyse : « Accepté » ou « Rejeté ». */
        public String analysisResult;
        public Quality() {}
    }
}
