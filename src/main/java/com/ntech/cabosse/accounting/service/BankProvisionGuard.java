package com.ntech.cabosse.accounting.service;

import com.ntech.cabosse.accounting.entity.SyscohadaAccounts;
import com.ntech.cabosse.accounting.repository.BankAccountRepository;
import com.ntech.cabosse.accounting.repository.JournalPieceRepository;
import com.ntech.cabosse.reception.entity.PaymentMethod;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.ErrorCode;
import com.ntech.cabosse.shared.i18n.Messages;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * L'avertissement de provision, commun à tous les flux qui sortent de
 * l'argent d'une banque (avances aux délégués, reliquats, crédits
 * producteurs).
 *
 * <p>Un chèque ou un virement qui dépasse le solde du compte expose la
 * structure : le refus porte le code {@link ErrorCode#TREASURY_INSUFFICIENT}
 * et se passe outre explicitement, une banque pouvant autoriser le
 * découvert. Le message ne cite aucun chiffre : le solde bancaire est une
 * information réservée, et celui qui décaisse ne la détient pas
 * forcément. Les espèces ne passent pas ici : la garde de caisse
 * négative, absolue, vit au point de passage des écritures et ne se lève
 * jamais.</p>
 */
@ApplicationScoped
public class BankProvisionGuard {

    @Inject BankAccountRepository bankAccounts;
    @Inject JournalPieceRepository pieces;

    public void warnIfBankCannotCover(PaymentMethod method, UUID bankAccountId,
                                      BigDecimal amount, BigDecimal fees,
                                      Boolean acknowledged) {
        if (method == PaymentMethod.CASH) return;
        if (Boolean.TRUE.equals(acknowledged)) return;
        String account = bankAccountId != null
                ? bankAccounts.findById(bankAccountId)
                        .map(b -> b.syscohadaAccount).orElse(null)
                : SyscohadaAccounts.BANQUE_DEFAULT;
        if (account == null) return;
        BigDecimal needed = amount != null ? amount : BigDecimal.ZERO;
        if (fees != null) needed = needed.add(fees);
        BigDecimal balance = pieces.balance(account, LocalDate.now());
        if (balance == null || balance.compareTo(needed) < 0) {
            throw new BusinessException(ErrorCode.TREASURY_INSUFFICIENT,
                    Messages.msg("m.col-disburse-insufficient"));
        }
    }
}
