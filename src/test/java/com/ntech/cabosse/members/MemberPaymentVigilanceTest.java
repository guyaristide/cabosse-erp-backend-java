package com.ntech.cabosse.members;

import com.ntech.cabosse.members.entity.MemberEntity;
import com.ntech.cabosse.members.entity.MemberIdentityDocument;
import com.ntech.cabosse.members.service.MemberPaymentVigilance;
import com.ntech.cabosse.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MEM-12 — vigilance sur les paiements aux producteurs. Test unitaire pur :
 * la règle ne dépend ni de Mongo ni du contexte tenant, seul son
 * déclenchement est piloté par la préférence du tenant.
 */
class MemberPaymentVigilanceTest {

    private MemberEntity memberWithScan() {
        MemberEntity m = new MemberEntity();
        m.name = "Konan N'Guessan";
        m.identityDocuments = List.of(
                new MemberIdentityDocument("CNI", "CI001", UUID.randomUUID()));
        return m;
    }

    @Test
    void a_scanned_identity_document_is_required() {
        MemberEntity sansScan = new MemberEntity();
        sansScan.name = "Konan N'Guessan";
        sansScan.identityDocuments = List.of(new MemberIdentityDocument("CNI", "CI001", null));

        assertThatThrownBy(() -> MemberPaymentVigilance.check(sansScan, "CASH"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("pièce d'identité scannée");

        assertThatCode(() -> MemberPaymentVigilance.check(memberWithScan(), "CASH"))
                .doesNotThrowAnyException();
    }

    @Test
    void the_legacy_id_card_file_still_counts_as_a_scan() {
        MemberEntity m = new MemberEntity();
        m.name = "Ama Koffi";
        m.idCardFileId = UUID.randomUUID();

        assertThatCode(() -> MemberPaymentVigilance.check(m, "CASH"))
                .doesNotThrowAnyException();
    }

    @Test
    void paying_a_third_party_account_by_mobile_money_requires_a_mandate() {
        MemberEntity m = memberWithScan();
        m.mobileMoneyHolderName = "Yao Kouassi";

        assertThatThrownBy(() -> MemberPaymentVigilance.check(m, "MOBILE_MONEY"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("mandat écrit");

        m.mobileMoneyMandateOnFile = true;
        assertThatCode(() -> MemberPaymentVigilance.check(m, "MOBILE_MONEY"))
                .doesNotThrowAnyException();
    }

    @Test
    void an_account_held_by_the_producer_needs_no_mandate() {
        MemberEntity m = memberWithScan();
        // Même nom à la casse et aux accents près : c'est bien le producteur.
        m.mobileMoneyHolderName = "  konan n'guessan ";

        assertThatCode(() -> MemberPaymentVigilance.check(m, "MOBILE_MONEY"))
                .doesNotThrowAnyException();
    }

    @Test
    void a_cash_payment_ignores_the_mobile_money_holder() {
        MemberEntity m = memberWithScan();
        m.mobileMoneyHolderName = "Yao Kouassi";

        assertThatCode(() -> MemberPaymentVigilance.check(m, "CASH"))
                .doesNotThrowAnyException();
    }
}
