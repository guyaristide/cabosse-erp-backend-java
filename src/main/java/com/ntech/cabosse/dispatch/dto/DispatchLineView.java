package com.ntech.cabosse.dispatch.dto;

import com.ntech.cabosse.dispatch.entity.DispatchLine;

import java.math.BigDecimal;
import java.util.UUID;

/** Une ligne du bordereau, telle qu'elle a été écrite (CE-195). */
public record DispatchLineView(
        UUID receiptId,
        String receiptRef,
        String lotRef,
        BigDecimal grossKg,
        Integer bagsCount,
        BigDecimal netKg,
        BigDecimal cmupAtDispatch
) {
    public static DispatchLineView from(DispatchLine l) {
        return new DispatchLineView(l.receiptId, l.receiptRef, l.lotRef,
                l.grossKg, l.bagsCount, l.netKg, l.cmupAtDispatch);
    }
}
