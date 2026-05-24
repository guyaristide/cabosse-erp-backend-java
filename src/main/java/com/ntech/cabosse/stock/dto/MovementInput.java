package com.ntech.cabosse.stock.dto;

import com.ntech.cabosse.stock.entity.MovementKind;
import com.ntech.cabosse.stock.entity.MovementSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Données nécessaires pour appliquer un mouvement de stock.
 *
 * <p>Utilisé en interne par les services qui produisent des mouvements
 * (DirectReceiptService, PurchaseOrderService, StockService lui-même
 * pour les saisies manuelles, etc.). Pas exposé directement par le
 * resource REST — chaque endpoint a son propre DTO de surface adapté à
 * son contexte métier.</p>
 *
 * <p>{@code quantity} est <strong>toujours positive</strong> sauf pour
 * un {@link MovementKind#ADJUSTMENT} où elle est signée. Le signe final
 * appliqué au stock dépend du {@code kind}.</p>
 */
public record MovementInput(
        UUID articleId,
        UUID siteId,
        MovementKind kind,
        BigDecimal quantity,
        /** PU FCFA — requis pour IN/OPENING/TRANSFER_IN, ignoré pour ADJUSTMENT. */
        BigDecimal unitPriceFcfa,
        MovementSource sourceType,
        String sourceRef,
        UUID sourceEntityId,
        /** Lie les deux branches d'un transfert. Null sinon. */
        UUID transferId,
        /** Obligatoire pour ADJUSTMENT. */
        String reason,
        String notes,
        /** Date d'effet (peut être rétroactive). Si null, {@code Instant.now()}. */
        Instant occurredAt,
        /**
         * Bypass le contrôle {@code ALLOW_NEGATIVE_STOCK} sur les OUT.
         * Réservé aux compensations de contre-passation et autres actions
         * système où la cohérence avec la transaction d'origine prime sur
         * la disponibilité physique actuelle. Audit conservé via
         * {@code sourceEntityId}.
         */
        boolean force,
        /**
         * Étiquette de lot — renseignée par la production sur le mouvement
         * IN du PF, transmise au mouvement pour traçabilité ultérieure.
         */
        String lotRef
) {
    /**
     * Constructeur compat sans {@code force} ni {@code lotRef} — défauts
     * {@code false}/{@code null}. Pour les call-sites non-compensatoires
     * et hors-production.
     */
    public MovementInput(UUID articleId, UUID siteId, MovementKind kind,
                          BigDecimal quantity, BigDecimal unitPriceFcfa,
                          MovementSource sourceType, String sourceRef,
                          UUID sourceEntityId, UUID transferId,
                          String reason, String notes, Instant occurredAt) {
        this(articleId, siteId, kind, quantity, unitPriceFcfa,
                sourceType, sourceRef, sourceEntityId, transferId,
                reason, notes, occurredAt, false, null);
    }

    /**
     * Constructeur compat avec {@code force} mais sans {@code lotRef} —
     * pour les compensations de contre-passation existantes (RD/BC).
     */
    public MovementInput(UUID articleId, UUID siteId, MovementKind kind,
                          BigDecimal quantity, BigDecimal unitPriceFcfa,
                          MovementSource sourceType, String sourceRef,
                          UUID sourceEntityId, UUID transferId,
                          String reason, String notes, Instant occurredAt,
                          boolean force) {
        this(articleId, siteId, kind, quantity, unitPriceFcfa,
                sourceType, sourceRef, sourceEntityId, transferId,
                reason, notes, occurredAt, force, null);
    }
}
