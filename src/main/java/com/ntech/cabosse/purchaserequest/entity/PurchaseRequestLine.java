package com.ntech.cabosse.purchaserequest.entity;

import java.math.BigDecimal;
import java.util.UUID;

/** Ligne d'une demande d'achat. Embed dans {@link PurchaseRequestEntity}. */
public class PurchaseRequestLine {

    public UUID id;

    public UUID articleId;
    /** Snapshots catalogue pour rester lisible après renommage. */
    public String articleCode;
    public String designation;
    public String unit;

    public BigDecimal quantity;
    /** Prix unitaire estimé (indicatif — le BC portera le prix négocié). */
    public BigDecimal estimatedUnitPriceFcfa;

    /** {@code quantity × estimatedUnitPriceFcfa}. Pré-calculé. */
    public BigDecimal estimatedLineFcfa;

    public PurchaseRequestLine() {}
}
