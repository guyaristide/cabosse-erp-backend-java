package com.ntech.cabosse.cashforecast.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;

/** Une ligne du prévisionnel : un compte, un montant, une source de fonds. */
@Schema(description = "Ligne d'un prévisionnel de décaissement")
public record CashForecastLineDto(
        String account,
        String accountLabel,
        String detail,
        BigDecimal amount,
        /** BANK ou CASH. */
        String source
) {}
