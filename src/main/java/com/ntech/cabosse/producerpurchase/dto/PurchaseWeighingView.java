package com.ntech.cabosse.producerpurchase.dto;

import com.ntech.cabosse.producerpurchase.entity.PurchaseWeighing;

import java.math.BigDecimal;

/** Une pesée du bordereau, telle qu'elle a été écrite (CE-183). */
public record PurchaseWeighingView(
        BigDecimal grossKg,
        Integer bagsCount,
        BigDecimal deductionKg,
        BigDecimal netKg
) {
    public static PurchaseWeighingView from(PurchaseWeighing w) {
        return new PurchaseWeighingView(w.grossKg, w.bagsCount, w.deductionKg, w.netKg);
    }
}
