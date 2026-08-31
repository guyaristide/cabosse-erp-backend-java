package com.ntech.cabosse.accounting;

import com.ntech.cabosse.accounting.entity.SyscohadaAccounts;
import com.ntech.cabosse.accounting.service.AccountingService;
import com.ntech.cabosse.reception.entity.PaymentMethod;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le chèque et les frais bancaires.
 *
 * <p>Deux demandes de l'expert métier du 30/08/2026, qui se tiennent
 * ensemble. Le chèque n'est pas un virement : les bénéficiaires n'ayant
 * pas de compte en banque, on ne peut rien leur virer, mais on peut leur
 * remettre un chèque qu'ils encaissent au guichet. Et un virement coûte
 * des frais là où un chèque n'en coûte pas.</p>
 */
@QuarkusTest
class BankFeesAndChequeTest {

    @Inject AccountingService accounting;

    @Test
    void a_cheque_is_bank_money_like_a_transfer() {
        // Le chèque se dénoue sur la banque, comme le virement : ce qui
        // les distingue est l'instrument, pas la nature du compte.
        assertThat(accounting.treasuryAccountFor(PaymentMethod.CHEQUE, null))
                .isEqualTo(SyscohadaAccounts.BANQUE_DEFAULT)
                .isEqualTo(accounting.treasuryAccountFor(PaymentMethod.BANK_TRANSFER, null));
    }

    @Test
    void cash_is_the_only_one_that_leaves_the_till() {
        assertThat(accounting.treasuryAccountFor(PaymentMethod.CASH, null))
                .isEqualTo(SyscohadaAccounts.CAISSE_DEFAULT);
    }

    @ParameterizedTest
    @EnumSource(PaymentMethod.class)
    void every_method_resolves_to_a_treasury_account(PaymentMethod method) {
        // Une valeur ajoutée à l'énuméré sans être routée retomberait
        // silencieusement sur la banque ou lèverait : on tient les deux.
        assertThat(accounting.treasuryAccountFor(method, null))
                .isIn(SyscohadaAccounts.BANQUE_DEFAULT, SyscohadaAccounts.CAISSE_DEFAULT);
    }

    @Test
    void the_cheque_sits_between_the_till_and_the_transfer_in_the_list() {
        // L'ordre de l'énuméré est celui des sélecteurs : le chèque se lit
        // à côté du virement, pas relégué après « autre ».
        var values = java.util.List.of(PaymentMethod.values());
        assertThat(values.indexOf(PaymentMethod.CHEQUE))
                .isLessThan(values.indexOf(PaymentMethod.OTHER));
    }
}
