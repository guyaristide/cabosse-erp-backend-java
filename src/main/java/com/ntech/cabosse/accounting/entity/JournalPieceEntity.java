package com.ntech.cabosse.accounting.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pièce comptable — racine d'agrégat <strong>immuable</strong>. Tenant-scopée
 * (collection {@code journal_pieces}).
 *
 * <p>Une pièce est créée par {@code AccountingService.postPiece} en
 * réponse à un événement métier (BC livré, vente confirmée, paiement
 * enregistré…). Elle est équilibrée : somme débits == somme crédits,
 * sinon la création est refusée. <strong>Elle n'est jamais modifiée ni
 * supprimée</strong> : une erreur se corrige par contre-passation
 * (nouvelle pièce miroir avec {@link #reversedFromPieceId} pointant sur
 * l'originale).</p>
 *
 * <p>Idempotence : le couple {@code (sourceType, sourceId)} est indexé
 * unique. Si l'événement source est rejoué (ex. job qui retry une
 * livraison), {@code postPiece} no-op au lieu de doubler.</p>
 */
public class JournalPieceEntity {

    @BsonId
    public UUID id;

    /** Référence affichable {@code EC-YYYY-NNNNNN}. Unique par tenant. */
    public String ref;

    /** Date de comptabilisation (= date métier de l'événement source). */
    public LocalDate date;

    public PostingSourceType sourceType;

    /**
     * UUID de l'agrégat source ({@code PurchaseOrderEntity.id},
     * {@code SaleEntity.id}, ou {@code paymentId} d'un SalePayment). Sert
     * l'unicité idempotente et la navigation pièce ↔ source.
     */
    public UUID sourceId;

    /** Référence affichable de la source ({@code BC-2026-0001}, {@code FA-…}). */
    public String sourceRef;

    /** Libellé de la pièce ("Livraison BC-2026-0001 — SARL Cocoa Trade"). */
    public String libelle;

    public List<JournalEntry> entries = new ArrayList<>();

    /** Somme des débits. Recalculé à chaque save (en pratique : une seule fois, immuable). */
    public BigDecimal totalDebitFcfa = BigDecimal.ZERO;
    public BigDecimal totalCreditFcfa = BigDecimal.ZERO;

    /**
     * Pièce d'origine si celle-ci est une contre-passation. {@code null}
     * sur une pièce normale. Sur une contre-passation, {@link #sourceType}
     * est {@code XXX_REVERSAL} et {@link #sourceId} reste l'UUID de
     * l'agrégat métier annulé (pour faciliter la jointure côté requête).
     */
    public UUID reversedFromPieceId;

    public Instant createdAt;
    /** UUID du user déclencheur, ou {@code SYSTEM_USER_ID} si flow auto. */
    public UUID createdBy;
    /** Email snapshot pour affichage rapide. */
    public String createdByEmail;

    public JournalPieceEntity() {}
}
