package com.ntech.cabosse.reception.entity;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Une ligne d'une session de réception directe = un achat à un producteur
 * (ou fournisseur). Le produit est porté par la session, pas par la
 * ligne — toutes les lignes d'une session concernent le même article.
 *
 * <p>Snapshots fournisseur : on garde le nom au moment de la réception
 * pour rester lisible même si le fournisseur est renommé ou désactivé
 * plus tard.</p>
 */
public class DirectReceiptLine {

    public UUID id;

    /** FK vers {@code SupplierEntity}. */
    public UUID supplierId;
    /** Snapshot {@code SupplierEntity.code} au moment de la réception. */
    public String supplierCode;
    /** Snapshot {@code SupplierEntity.name}. */
    public String supplierName;

    public BigDecimal quantity;
    public BigDecimal unitPrice;
    /** {@code quantity × unitPrice}. Pré-calculé pour audit. */
    public BigDecimal totalLine;

    /** Référence du bon de livraison côté terrain (n° BL). */
    public String deliveryNoteRef;

    /**
     * Paiement de cette ligne. {@code null} = ligne due. Présent dès
     * qu'un règlement est enregistré, même partiel.
     */
    public DirectReceiptPayment payment;

    public String notes;

    public DirectReceiptLine() {}
}
