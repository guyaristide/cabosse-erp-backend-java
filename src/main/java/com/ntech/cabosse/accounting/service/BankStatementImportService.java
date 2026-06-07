package com.ntech.cabosse.accounting.service;

import com.ntech.cabosse.accounting.entity.BankStatementEntity;
import com.ntech.cabosse.accounting.entity.BankStatementLineEntity;
import com.ntech.cabosse.accounting.entity.BankStatementLineStatus;
import com.ntech.cabosse.accounting.entity.BankStatementStatus;
import com.ntech.cabosse.accounting.repository.BankAccountRepository;
import com.ntech.cabosse.accounting.repository.BankStatementLineRepository;
import com.ntech.cabosse.accounting.repository.BankStatementRepository;
import com.ntech.cabosse.shared.exception.BusinessException;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Import CSV d'extrait bancaire — format simple : 4 colonnes
 * {@code date;label;amount;direction} avec séparateur point-virgule (FR) ou
 * virgule (intl).
 *
 * <p>Format accepté pour les montants : virgule décimale FR ou point ;
 * un signe {@code -} en préfixe indique un débit, sinon crédit (la colonne
 * {@code direction} prime si renseignée).</p>
 *
 * <p>Format date accepté : ISO {@code YYYY-MM-DD} ou FR {@code DD/MM/YYYY}.</p>
 *
 * <p>Dédoublonnage par hash {@code (bankAccountId + date + label normalisé
 * + amount + direction)} — un même fichier réimporté n'ajoute pas de
 * doublons, on conserve les lignes existantes.</p>
 */
@ApplicationScoped
public class BankStatementImportService {

    private static final Logger LOG = Logger.getLogger(BankStatementImportService.class);

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter FR = DateTimeFormatter.ofPattern("dd/MM/uuuu");

    @Inject BankAccountRepository banks;
    @Inject BankStatementRepository statements;
    @Inject BankStatementLineRepository lines;
    @Inject IdGenerator idGenerator;
    @Inject JsonWebToken jwt;

    public ImportResult importCsv(UUID bankAccountId, String fileName, InputStream csv) {
        if (banks.findById(bankAccountId).isEmpty()) {
            throw new NotFoundException("Compte bancaire " + bankAccountId + " introuvable.");
        }
        List<ParsedLine> parsed = parse(csv);
        if (parsed.isEmpty()) {
            throw new BusinessException("Aucune ligne valide dans le fichier importé.");
        }

        // Construction de l'extrait
        BankStatementEntity stmt = new BankStatementEntity();
        stmt.id = idGenerator.newId();
        stmt.bankAccountId = bankAccountId;
        stmt.fileName = fileName;
        stmt.importedAt = Instant.now();
        stmt.importedByEmail = actor();
        stmt.status = BankStatementStatus.IMPORTED;
        stmt.periodFrom = parsed.stream().map(p -> p.date).min(LocalDate::compareTo).orElse(null);
        stmt.periodTo = parsed.stream().map(p -> p.date).max(LocalDate::compareTo).orElse(null);
        stmt.openingBalance = BigDecimal.ZERO;
        stmt.closingBalance = BigDecimal.ZERO;
        stmt.matchedCount = 0;

        int inserted = 0;
        int skipped = 0;
        List<BankStatementLineEntity> toInsert = new ArrayList<>();
        for (ParsedLine p : parsed) {
            String hash = hash(bankAccountId, p);
            if (lines.hashExists(bankAccountId, hash)) {
                skipped++;
                continue;
            }
            BankStatementLineEntity line = new BankStatementLineEntity();
            line.id = idGenerator.newId();
            line.statementId = stmt.id;
            line.bankAccountId = bankAccountId;
            line.operationDate = p.date;
            line.label = p.label;
            line.amountFcfa = p.amount.abs();
            line.direction = p.direction;
            line.status = BankStatementLineStatus.UNMATCHED;
            line.sourceHash = hash;
            line.createdAt = Instant.now();
            toInsert.add(line);
            inserted++;
        }

        if (inserted == 0) {
            throw new BusinessException(
                    "Toutes les lignes du fichier ont déjà été importées (doublons sur ce compte).");
        }

        stmt.lineCount = inserted;
        statements.insert(stmt);
        toInsert.forEach(lines::insert);

        LOG.infof("Extrait bancaire %s importé pour compte %s : %d ligne(s) ajoutée(s), %d doublon(s) ignoré(s)",
                stmt.id, bankAccountId, inserted, skipped);

        return new ImportResult(stmt.id, inserted, skipped);
    }

    public record ImportResult(UUID statementId, int linesInserted, int duplicatesSkipped) {}

    // ─── CSV parsing ────────────────────────────────────────────────

    private record ParsedLine(LocalDate date, String label, BigDecimal amount, String direction) {}

    private List<ParsedLine> parse(InputStream in) {
        List<ParsedLine> out = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) continue;
                // Skip ligne d'en-tête détectée par mot-clé "date"
                if (lineNo == 1 && line.toLowerCase(Locale.ROOT).contains("date")) continue;

                String[] parts = splitCsv(line);
                if (parts.length < 3) continue;
                LocalDate date = parseDate(parts[0]);
                if (date == null) continue;
                String label = parts[1].trim();
                BigDecimal amount = parseAmount(parts[2]);
                if (amount == null) continue;
                String direction = parts.length > 3 && !parts[3].isBlank()
                        ? parts[3].trim().toUpperCase(Locale.ROOT)
                        : (amount.signum() < 0 ? "DEBIT" : "CREDIT");
                out.add(new ParsedLine(date, label, amount, direction));
            }
        } catch (Exception e) {
            throw new BusinessException("Lecture CSV échouée : " + e.getMessage(), e);
        }
        return out;
    }

    /** Split CSV simple — supporte ; et , avec auto-détection sur la première ligne. */
    private static String[] splitCsv(String line) {
        char sep = line.contains(";") ? ';' : ',';
        return line.split("\\" + sep, -1);
    }

    private static LocalDate parseDate(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        try { return LocalDate.parse(trimmed, ISO); } catch (DateTimeParseException ignore) {}
        try { return LocalDate.parse(trimmed, FR); } catch (DateTimeParseException ignore) {}
        return null;
    }

    private static BigDecimal parseAmount(String s) {
        if (s == null || s.isBlank()) return null;
        String cleaned = s.trim()
                .replace(" ", "")
                .replace(" ", "")
                .replace(",", ".");
        try { return new BigDecimal(cleaned); }
        catch (NumberFormatException e) { return null; }
    }

    private String hash(UUID bankAccountId, ParsedLine p) {
        String payload = bankAccountId + "|" + p.date + "|"
                + p.label.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT)
                + "|" + p.amount.abs().toPlainString() + "|" + p.direction;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(payload.getBytes(StandardCharsets.UTF_8));
            // Hex compact des 12 premiers octets — 24 chars, collision quasi-nulle.
            StringBuilder sb = new StringBuilder(24);
            for (int i = 0; i < 12; i++) sb.append(String.format("%02x", digest[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private String actor() {
        try { return jwt.getName(); } catch (Exception e) { return null; }
    }
}
