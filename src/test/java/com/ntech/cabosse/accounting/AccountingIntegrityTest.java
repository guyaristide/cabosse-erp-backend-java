package com.ntech.cabosse.accounting;

import com.ntech.cabosse.auth.service.PasswordHasher;
import com.ntech.cabosse.shared.migration.TenantMigrationRunner;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import com.ntech.cabosse.shared.security.Roles;
import com.ntech.cabosse.tenant.entity.TenantEntity;
import com.ntech.cabosse.test.AbstractIntegrationTest;
import com.ntech.cabosse.test.MongoReplicaSetTestResource;
import com.ntech.cabosse.test.TestFixtures;
import com.ntech.cabosse.user.entity.UserEntity;
import com.ntech.cabosse.user.entity.UserStatus;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Intégrité de la comptabilité : ce qui est écrit au journal est équilibré,
 * et ce que les états exportés racontent correspond à ce journal.
 *
 * <p>C'est le filet qui manquait. Une écriture déséquilibrée ou un état qui
 * perd des lignes ne se voit pas à l'écran : cela se découvre à la clôture,
 * chez le comptable, quand il est trop tard pour savoir quelle opération a
 * dérapé. Ces contrôles valent pour toutes les écritures, quelle que soit
 * l'opération qui les a produites.</p>
 */
@QuarkusTest
@QuarkusTestResource(MongoReplicaSetTestResource.class)
class AccountingIntegrityTest extends AbstractIntegrationTest {

    @Inject PasswordHasher passwordHasher;
    @Inject IdGenerator idGenerator;
    @Inject TenantMigrationRunner migrations;

    // ─── Mise en place ──────────────────────────────────────────────

    private UserEntity tenantAdmin() {
        TenantEntity tenant = fixtures.createActiveTenant(
                "coop-compta-" + TestFixtures.randomSlugSuffix(), "Coopérative Comptabilité");
        // Les états s'appuient sur le plan de comptes semé par les migrations.
        migrations.runMigrationsFor(tenant.databaseName);

        UserEntity u = new UserEntity();
        u.id = idGenerator.newId();
        u.email = "admin@" + tenant.slug + ".ci";
        u.firstName = "Admin";
        u.lastName = "Tenant";
        u.passwordHash = passwordHasher.hash(TestFixtures.DEFAULT_PASSWORD);
        u.tenantId = tenant.id;
        u.roles = new HashSet<>();
        u.roles.add(Roles.TENANT_ADMIN);
        u.status = UserStatus.ACTIVE;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        users.persist(u);
        return u;
    }

    private String createSite(UserEntity admin) {
        String code = "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        return givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Entrepôt\",\"type\":\"TRANSFORMATION\",\"code\":\"" + code + "\"}")
                .when().post("/api/v1/sites").then().statusCode(201).extract().path("data.id");
    }

    private String createCustomer(UserEntity admin) {
        return givenAs(admin).contentType("application/json")
                .body("{\"name\":\"Client Négoce\",\"type\":\"COMPANY\"}")
                .when().post("/api/v1/customers").then().statusCode(201).extract().path("data.id");
    }

    private String createArticle(UserEntity admin, String name) {
        return givenAs(admin).contentType("application/json")
                .body("""
                        { "type": "FINISHED_PRODUCT", "name": "%s", "unit": "kg" }
                        """.formatted(name))
                .when().post("/api/v1/articles").then().statusCode(201).extract().path("data.id");
    }

    private void recordSale(UserEntity admin, String siteId, String customerId,
                            String articleId, int qty, int unitPrice) {
        givenAs(admin).contentType("application/json")
                .body("""
                        { "siteId": "%s", "channel": "B2B", "customerId": "%s", "saleDate": "%s",
                          "lines": [ { "articleId": "%s", "quantity": %d, "unitPrice": %d } ] }
                        """.formatted(siteId, customerId, LocalDate.now(), articleId, qty, unitPrice))
                .when().post("/api/v1/sales?asQuote=false")
                .then().statusCode(201);
    }

    /** Une charge validée au journal : donne au compte de résultat de quoi parler. */
    private void postValidatedExpense(UserEntity admin, int amount) {
        String id = givenAs(admin).contentType("application/json")
                .body("""
                        { "date": "%s", "libelle": "Frais de campagne",
                          "lines": [
                            { "account": "601000", "libelle": "Achat", "debit": %d },
                            { "account": "401000", "libelle": "Fournisseur", "credit": %d }
                          ] }
                        """.formatted(LocalDate.now(), amount, amount))
                .when().post("/api/v1/accounting/od").then().statusCode(201)
                .extract().path("data.id");
        givenAs(admin).contentType("application/json")
                .when().post("/api/v1/accounting/od/" + id + "/validate")
                .then().statusCode(200);
    }

    /** Jeu d'écritures représentatif : une vente, deux charges. */
    private void seedJournal(UserEntity admin) {
        String siteId = createSite(admin);
        String customerId = createCustomer(admin);
        String articleId = createArticle(admin, "Cacao marchand");
        recordSale(admin, siteId, customerId, articleId, 10, 2000);
        postValidatedExpense(admin, 75_000);
        postValidatedExpense(admin, 12_500);
    }

    private JsonPath journal(UserEntity admin) {
        return givenAs(admin)
                .when().get("/api/v1/accounting/journal?perPage=100")
                .then().statusCode(200)
                .extract().jsonPath();
    }

    // ─── Contrôles ──────────────────────────────────────────────────

    @Test
    void toute_piece_du_journal_est_equilibree() {
        UserEntity admin = tenantAdmin();
        seedJournal(admin);

        JsonPath j = journal(admin);
        List<Map<String, Object>> pieces = j.getList("data.items");
        assertFalse(pieces.isEmpty(), "Le jeu d'essai doit produire des écritures");

        for (int i = 0; i < pieces.size(); i++) {
            BigDecimal totalDebit = dec(j.get("data.items[" + i + "].totalDebit"));
            BigDecimal totalCredit = dec(j.get("data.items[" + i + "].totalCredit"));
            String ref = j.getString("data.items[" + i + "].ref");

            assertEquals(0, totalDebit.compareTo(totalCredit),
                    "Pièce " + ref + " déséquilibrée : débit " + totalDebit
                            + " contre crédit " + totalCredit);

            // Les totaux portés par la pièce doivent aussi correspondre à ses
            // propres lignes : un total juste sur des lignes fausses reste faux.
            BigDecimal sumDebit = sum(j.getList("data.items[" + i + "].entries.debit"));
            BigDecimal sumCredit = sum(j.getList("data.items[" + i + "].entries.credit"));
            assertEquals(0, sumDebit.compareTo(totalDebit),
                    "Pièce " + ref + " : total débit incohérent avec ses lignes");
            assertEquals(0, sumCredit.compareTo(totalCredit),
                    "Pièce " + ref + " : total crédit incohérent avec ses lignes");
            assertTrue(totalDebit.signum() > 0,
                    "Pièce " + ref + " sans montant : une écriture vide n'a rien à faire au journal");
        }
    }

    @Test
    void la_balance_generale_reprend_exactement_le_journal() {
        UserEntity admin = tenantAdmin();
        seedJournal(admin);

        BigDecimal journalDebit = BigDecimal.ZERO;
        BigDecimal journalCredit = BigDecimal.ZERO;
        JsonPath j = journal(admin);
        int count = j.getList("data.items").size();
        for (int i = 0; i < count; i++) {
            journalDebit = journalDebit.add(dec(j.get("data.items[" + i + "].totalDebit")));
            journalCredit = journalCredit.add(dec(j.get("data.items[" + i + "].totalCredit")));
        }

        List<String[]> rows = csv(admin, "/api/v1/accounting/export/balance?format=csv");
        BigDecimal balanceDebit = BigDecimal.ZERO;
        BigDecimal balanceCredit = BigDecimal.ZERO;
        for (String[] row : rows) {
            balanceDebit = balanceDebit.add(frenchNumber(row[3]));
            balanceCredit = balanceCredit.add(frenchNumber(row[4]));
        }

        assertEquals(0, balanceDebit.compareTo(balanceCredit), "La balance générale doit être équilibrée");
        assertEquals(0, balanceDebit.compareTo(journalDebit),
                "La balance perd ou invente des débits par rapport au journal");
        assertEquals(0, balanceCredit.compareTo(journalCredit),
                "La balance perd ou invente des crédits par rapport au journal");
    }

    @Test
    void le_grand_livre_d_un_compte_recoupe_sa_ligne_de_balance() {
        UserEntity admin = tenantAdmin();
        seedJournal(admin);

        // 401000 est mouvementé par les deux charges du jeu d'essai.
        String account = "401000";
        String[] balanceRow = csv(admin, "/api/v1/accounting/export/balance?format=csv").stream()
                .filter(r -> account.equals(r[0]))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Compte " + account + " absent de la balance"));

        List<String[]> ledger = csv(admin,
                "/api/v1/accounting/export/grand-livre?account=" + account + "&format=csv");
        assertFalse(ledger.isEmpty(), "Le grand-livre du compte doit porter ses mouvements");

        BigDecimal ledgerCredit = BigDecimal.ZERO;
        for (String[] line : ledger) {
            ledgerCredit = ledgerCredit.add(firstNumberFrom(line));
        }
        assertEquals(0, frenchNumber(balanceRow[4]).compareTo(ledgerCredit),
                "Le grand-livre et la balance ne racontent pas la même chose sur " + account);
    }

    @Test
    void le_fec_porte_une_ligne_par_ecriture() {
        UserEntity admin = tenantAdmin();
        seedJournal(admin);

        int entries = 0;
        JsonPath j = journal(admin);
        int pieces = j.getList("data.items").size();
        for (int i = 0; i < pieces; i++) {
            entries += j.getList("data.items[" + i + "].entries").size();
        }

        String fec = givenAs(admin)
                .when().get("/api/v1/accounting/export/fec")
                .then().statusCode(200)
                .extract().asString();

        List<String> lines = fec.lines().filter(l -> !l.isBlank()).toList();
        assertEquals(18, lines.get(0).split("\\|", -1).length,
                "L'en-tête FEC doit porter ses 18 colonnes réglementaires");
        assertEquals(entries, lines.size() - 1,
                "Le FEC doit porter exactement une ligne par écriture du journal");
    }

    @Test
    void le_compte_de_resultat_deduit_les_charges_des_produits() {
        UserEntity admin = tenantAdmin();
        seedJournal(admin);

        List<String[]> rows = csv(admin, "/api/v1/accounting/export/compte-resultat?format=csv");
        BigDecimal charges = rowAmount(rows, "TOTAL CHARGES");
        BigDecimal produits = rowAmount(rows, "TOTAL PRODUITS");
        BigDecimal resultat = rowAmount(rows, "RÉSULTAT NET (produits − charges)");

        assertEquals(0, produits.subtract(charges).compareTo(resultat),
                "Le résultat net doit valoir les produits moins les charges");
        // Le jeu d'essai porte 87 500 de charges et une vente de 20 000 :
        // le résultat est négatif, ce qui vérifie aussi le signe.
        assertTrue(resultat.signum() < 0, "Charges supérieures aux produits attendues ici");
    }

    @Test
    void les_etats_restent_servis_sur_une_comptabilite_vide() {
        UserEntity admin = tenantAdmin();

        // Un tenant qui vient d'être créé ouvre ses états avant d'avoir saisi
        // quoi que ce soit : aucun de ces écrans ne doit tomber.
        for (String path : List.of(
                "/api/v1/accounting/export/balance?format=csv",
                "/api/v1/accounting/export/compte-resultat?format=csv",
                "/api/v1/accounting/export/bilan?format=csv",
                "/api/v1/accounting/export/journal?format=csv")) {
            givenAs(admin).when().get(path).then().statusCode(200);
        }
        givenAs(admin).when().get("/api/v1/accounting/export/fec").then().statusCode(200);
    }

    // ─── Lecture des exports ────────────────────────────────────────

    /** Lignes de données d'un export CSV (BOM et en-tête retirés). */
    private List<String[]> csv(UserEntity admin, String path) {
        String body = givenAs(admin).when().get(path)
                .then().statusCode(200)
                .extract().asString();
        if (!body.isEmpty() && body.charAt(0) == '﻿') body = body.substring(1);
        List<String> lines = body.lines().filter(l -> !l.isBlank()).toList();
        List<String[]> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            rows.add(splitCsv(lines.get(i)));
        }
        return rows;
    }

    /** Découpe une ligne CSV en respectant les cellules entre guillemets. */
    private static String[] splitCsv(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString());
        return cells.toArray(new String[0]);
    }

    /**
     * Relit un nombre écrit à la française (espace insécable en séparateur
     * de milliers, virgule décimale), tel que les exports le produisent.
     */
    private static BigDecimal frenchNumber(String raw) {
        if (raw == null) return BigDecimal.ZERO;
        String cleaned = raw.replace(" ", "").replace(" ", "")
                .replace(" ", "").replace(",", ".").trim();
        if (cleaned.isEmpty()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /** Premier montant non nul d'une ligne, quel que soit son rang de colonne. */
    private static BigDecimal firstNumberFrom(String[] cells) {
        for (String cell : cells) {
            BigDecimal v = frenchNumber(cell);
            if (v.signum() != 0) return v;
        }
        return BigDecimal.ZERO;
    }

    private static BigDecimal rowAmount(List<String[]> rows, String rubrique) {
        for (String[] r : rows) {
            if (r.length >= 3 && rubrique.equals(r[1])) return frenchNumber(r[2]);
        }
        throw new AssertionError("Rubrique « " + rubrique + " » absente de l'état");
    }

    private static BigDecimal dec(Object v) {
        return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString());
    }

    private static BigDecimal sum(List<?> values) {
        BigDecimal total = BigDecimal.ZERO;
        if (values == null) return total;
        for (Object v : values) {
            if (v != null) total = total.add(new BigDecimal(v.toString()));
        }
        return total;
    }
}
