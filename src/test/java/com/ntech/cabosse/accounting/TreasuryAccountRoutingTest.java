package com.ntech.cabosse.accounting;

import com.ntech.cabosse.accounting.entity.BankAccountEntity;
import com.ntech.cabosse.accounting.entity.BankAccountKind;
import com.ntech.cabosse.accounting.entity.SyscohadaAccounts;
import com.ntech.cabosse.accounting.repository.BankAccountRepository;
import com.ntech.cabosse.accounting.service.AccountingService;
import com.ntech.cabosse.reception.entity.PaymentMethod;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.tenant.TenantContext;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.test.AbstractIntegrationTest;
import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import com.ntech.cabosse.test.TestFixtures;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L'argent atterrit sur le compte qui l'a réellement reçu.
 *
 * <p>Une coopérative tient souvent plusieurs caisses, et plusieurs banques
 * sous des sous-comptes distincts du compte principal. Tous les règlements
 * passaient pourtant par une correspondance codée en dur : espèces vers le
 * compte de caisse par défaut, tout le reste vers la banque par défaut.
 * Les sous-comptes ouverts par la structure seraient donc restés à zéro,
 * et la séparation n'aurait été que d'affichage.</p>
 *
 * <p>Le compte désigné l'emporte ; en son absence, le mode de paiement
 * décide comme avant, pour que les écritures déjà passées gardent leur
 * sens et qu'une structure à une seule caisse n'ait rien à renseigner.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class TreasuryAccountRoutingTest extends AbstractIntegrationTest {

    @Inject AccountingService accounting;
    @Inject BankAccountRepository bankAccounts;
    @Inject TenantContext tenantContext;
    @Inject IdGenerator idGenerator;

    private TenantEntity tenant;

    @BeforeEach
    void setUp() {
        tenant = fixtures.createActiveTenant(
                "coop-tres-" + TestFixtures.randomSlugSuffix(), "Structure Trésorerie");
        tenantContext.initializeForExecutor(
                tenant.id, tenant.databaseName, null, java.util.Set.of());
    }

    private UUID declareAccount(String syscohada, BankAccountKind kind, String label) {
        BankAccountEntity e = new BankAccountEntity();
        e.id = idGenerator.newId();
        e.bankName = label;
        e.label = label;
        e.syscohadaAccount = syscohada;
        e.kind = kind;
        e.active = true;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        bankAccounts.insert(e);
        return e.id;
    }

    @Test
    void a_cash_payment_lands_on_the_cash_box_that_received_it() {
        UUID premiere = declareAccount("571100", BankAccountKind.CAISSE, "Caisse principale");
        UUID seconde = declareAccount("571200", BankAccountKind.CAISSE, "Caisse secondaire");

        assertThat(accounting.treasuryAccountFor(PaymentMethod.CASH, premiere)).isEqualTo("571100");
        assertThat(accounting.treasuryAccountFor(PaymentMethod.CASH, seconde)).isEqualTo("571200");
    }

    @Test
    void a_transfer_lands_on_the_bank_that_received_it() {
        // Les intitulés appartiennent à la structure : le produit ne
        // connaît aucun nom de banque.
        UUID premiere = declareAccount("521100", BankAccountKind.BANQUE, "Banque A");
        UUID seconde = declareAccount("521200", BankAccountKind.BANQUE, "Banque B");

        assertThat(accounting.treasuryAccountFor(PaymentMethod.BANK_TRANSFER, premiere)).isEqualTo("521100");
        assertThat(accounting.treasuryAccountFor(PaymentMethod.BANK_TRANSFER, seconde)).isEqualTo("521200");
    }

    @Test
    void without_a_designated_account_the_payment_method_still_decides() {
        // Une structure à une seule caisse et une seule banque n'a rien à
        // désigner : le comportement d'avant reste le comportement par
        // défaut.
        assertThat(accounting.treasuryAccountFor(PaymentMethod.CASH, null))
                .isEqualTo(SyscohadaAccounts.CAISSE_DEFAULT);
        assertThat(accounting.treasuryAccountFor(PaymentMethod.BANK_TRANSFER, null))
                .isEqualTo(SyscohadaAccounts.BANQUE_DEFAULT);
    }

    @Test
    void an_unknown_account_is_refused_rather_than_silently_ignored() {
        // Retomber sur le compte par défaut ferait atterrir l'argent
        // ailleurs que là où l'opérateur l'a désigné, sans rien dire.
        UUID inconnu = UUID.randomUUID();
        try {
            accounting.treasuryAccountFor(PaymentMethod.CASH, inconnu);
            assertThat(false).as("un compte inconnu doit être refusé").isTrue();
        } catch (RuntimeException expected) {
            assertThat(expected.getMessage()).contains(inconnu.toString());
        }
    }
}
