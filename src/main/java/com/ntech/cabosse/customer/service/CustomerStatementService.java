package com.ntech.cabosse.customer.service;

import com.ntech.cabosse.campaign.entity.CampaignEntity;
import com.ntech.cabosse.campaign.repository.CampaignRepository;
import com.ntech.cabosse.commodity.entity.CommoditySaleEntity;
import com.ntech.cabosse.commodity.entity.CommoditySalePayment;
import com.ntech.cabosse.commodity.entity.SalesContractEntity;
import com.ntech.cabosse.commodity.repository.CommoditySaleRepository;
import com.ntech.cabosse.commodity.repository.SalesContractRepository;
import com.ntech.cabosse.customer.dto.CustomerStatementDto;
import com.ntech.cabosse.customer.entity.CustomerEntity;
import com.ntech.cabosse.customer.repository.CustomerRepository;
import com.ntech.cabosse.sale.entity.SaleEntity;
import com.ntech.cabosse.sale.entity.SalePayment;
import com.ntech.cabosse.sale.entity.SaleStatus;
import com.ntech.cabosse.sale.repository.SaleRepository;
import com.ntech.cabosse.shared.exception.NotFoundException;
import com.ntech.cabosse.shared.i18n.Messages;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * L'état d'un client, composé à la lecture.
 *
 * <p>Rien n'est stocké : ventes, ventes en gros et contrats vivent chacun
 * chez eux, et l'état les lit tels qu'ils sont. Un client a quelques
 * centaines d'opérations au plus, les relire à chaque ouverture coûte
 * moins qu'un cumul à tenir à jour partout où l'on écrit.</p>
 */
@ApplicationScoped
public class CustomerStatementService {

    private static final int OPERATIONS_LIMIT = 300;

    @Inject CustomerRepository customers;
    @Inject SaleRepository sales;
    @Inject CommoditySaleRepository commoditySales;
    @Inject SalesContractRepository contracts;
    @Inject CampaignRepository campaigns;

    public CustomerStatementDto compute(UUID customerId, UUID campaignId) {
        return compute(customerId, campaignId, OPERATIONS_LIMIT);
    }

    /** Le fil entier, pour l'export : la borne du fil à l'écran ne s'y applique pas. */
    public List<CustomerStatementDto.Operation> operationsForExport(UUID customerId, UUID campaignId) {
        return compute(customerId, campaignId, Integer.MAX_VALUE).operations();
    }

    private CustomerStatementDto compute(UUID customerId, UUID campaignId, int operationsLimit) {
        CustomerEntity c = customers.findById(customerId)
                .orElseThrow(() -> new NotFoundException(Messages.msg("m.cst-customer-not-found", customerId)));
        Map<UUID, CampaignEntity> byCampaign = new HashMap<>();
        for (CampaignEntity ce : campaigns.listAll()) byCampaign.put(ce.id, ce);
        CampaignEntity selected = campaignId == null ? null : byCampaign.get(campaignId);

        // Une vente annulée reste au registre mais ne pèse sur rien.
        List<SaleEntity> allSales = sales.search(null, null, null, c.id).stream()
                .filter(s -> s.status != SaleStatus.CANCELLED).toList();
        List<CommoditySaleEntity> allCommodity = commoditySales.search(null, null, c.id, 0, Integer.MAX_VALUE);
        List<SalesContractEntity> allContracts = contracts.list(null, c.id);

        // Les lignes par campagne, toutes confondues, dans l'ordre des campagnes.
        Map<UUID, Agg> perCampaign = new LinkedHashMap<>();
        for (SaleEntity s : allSales) {
            if (s.status == SaleStatus.QUOTE) continue;
            Agg a = perCampaign.computeIfAbsent(s.campaignId, k -> new Agg());
            a.sales++;
            a.invoiced = a.invoiced.add(nz(s.totalTtc));
            a.paid = a.paid.add(nz(s.totalPaid));
        }
        for (CommoditySaleEntity cs : allCommodity) {
            Agg a = perCampaign.computeIfAbsent(cs.campaignId, k -> new Agg());
            a.sales++;
            a.invoiced = a.invoiced.add(nz(cs.amountInvoicedTtc));
            a.paid = a.paid.add(nz(cs.totalPaid));
            a.commodityKg = a.commodityKg.add(acceptedKg(cs));
        }
        List<CustomerStatementDto.CampaignRow> rows = new ArrayList<>();
        for (Map.Entry<UUID, Agg> e : perCampaign.entrySet()) {
            Agg a = e.getValue();
            rows.add(new CustomerStatementDto.CampaignRow(
                    e.getKey(), labelOf(byCampaign, e.getKey()),
                    a.sales, a.invoiced, a.paid, a.invoiced.subtract(a.paid).max(BigDecimal.ZERO),
                    a.commodityKg));
        }
        rows.sort(Comparator.comparing((CustomerStatementDto.CampaignRow r) -> startOf(byCampaign, r.campaignId())).reversed());

        // Les totaux portent sur la campagne retenue, ou sur tout l'historique.
        List<SaleEntity> sIn = allSales.stream().filter(s -> inScope(s.campaignId, campaignId)).toList();
        List<CommoditySaleEntity> cIn = allCommodity.stream().filter(cs -> inScope(cs.campaignId, campaignId)).toList();
        List<SalesContractEntity> kIn = allContracts.stream().filter(k -> inScope(k.campaignId, campaignId)).toList();

        // Un devis n'engage rien : il se compte, il ne se facture pas.
        List<SaleEntity> quotes = sIn.stream().filter(s -> s.status == SaleStatus.QUOTE).toList();
        List<SaleEntity> firm = sIn.stream().filter(s -> s.status != SaleStatus.QUOTE).toList();
        BigDecimal salesAmount = sum(firm, s -> s.totalTtc);
        BigDecimal salesPaid = sum(firm, s -> s.totalPaid);
        LocalDate today = LocalDate.now();
        BigDecimal overdue = BigDecimal.ZERO;
        for (SaleEntity s : firm) {
            if (s.dueDate != null && s.dueDate.isBefore(today) && nz(s.totalTtc).compareTo(nz(s.totalPaid)) > 0) {
                overdue = overdue.add(nz(s.totalTtc).subtract(nz(s.totalPaid)));
            }
        }
        BigDecimal commodityKg = BigDecimal.ZERO;
        for (CommoditySaleEntity cs : cIn) commodityKg = commodityKg.add(acceptedKg(cs));
        BigDecimal commodityAmount = sum(cIn, cs -> cs.amountInvoicedTtc);
        BigDecimal commodityPaid = sum(cIn, cs -> cs.totalPaid);
        BigDecimal invoiced = salesAmount.add(commodityAmount);
        BigDecimal paid = salesPaid.add(commodityPaid);
        CustomerStatementDto.Totals totals = new CustomerStatementDto.Totals(
                quotes.size(), firm.size(), salesAmount, salesPaid,
                cIn.size(), commodityKg, commodityAmount, commodityPaid,
                invoiced, paid, invoiced.subtract(paid).max(BigDecimal.ZERO), overdue,
                kIn.size());

        // Le fil : chaque opération à sa date, la plus récente en tête.
        List<CustomerStatementDto.Operation> ops = new ArrayList<>();
        for (SaleEntity s : firm) {
            ops.add(new CustomerStatementDto.Operation("SALE", s.saleDate, s.ref,
                    s.invoiceNumber != null ? s.invoiceNumber : "", null, s.totalTtc,
                    labelOf(byCampaign, s.campaignId), s.id, "SALE"));
            if (s.payments == null) continue;
            for (SalePayment p : s.payments) {
                ops.add(new CustomerStatementDto.Operation("PAYMENT", p.paidOn, s.ref,
                        p.paymentNoteRef != null ? p.paymentNoteRef : "", null, p.amount,
                        labelOf(byCampaign, s.campaignId), s.id, "SALE"));
            }
        }
        for (CommoditySaleEntity cs : cIn) {
            ops.add(new CustomerStatementDto.Operation("COMMODITY_SALE", cs.date, cs.ref,
                    cs.articleName != null ? cs.articleName : "", acceptedKg(cs), cs.amountInvoicedTtc,
                    labelOf(byCampaign, cs.campaignId), cs.id, "COMMODITY_SALE"));
            if (cs.payments == null) continue;
            for (CommoditySalePayment p : cs.payments) {
                ops.add(new CustomerStatementDto.Operation("PAYMENT", p.paidOn, cs.ref,
                        p.paymentRef != null ? p.paymentRef : "", null, p.amount,
                        labelOf(byCampaign, cs.campaignId), cs.id, "COMMODITY_SALE"));
            }
        }
        for (SalesContractEntity k : kIn) {
            LocalDate date = k.createdAt == null ? null : LocalDate.ofInstant(k.createdAt, java.time.ZoneOffset.UTC);
            ops.add(new CustomerStatementDto.Operation("CONTRACT", date, k.ref,
                    k.label != null ? k.label : "", null, k.marginPerKg,
                    labelOf(byCampaign, k.campaignId), k.id, "CONTRACT"));
        }
        ops.sort(Comparator.comparing((CustomerStatementDto.Operation o) -> o.date() == null ? LocalDate.MIN : o.date()).reversed()
                .thenComparing(o -> o.ref() == null ? "" : o.ref(), Comparator.reverseOrder()));
        if (ops.size() > operationsLimit) ops = new ArrayList<>(ops.subList(0, operationsLimit));

        CustomerStatementDto.Header header = new CustomerStatementDto.Header(
                c.id, c.code, c.name, c.type, c.phone, c.cityName, c.subsidiaryAccount,
                c.creditLimit, c.active);
        return new CustomerStatementDto(header, totals, rows, ops,
                selected != null ? selected.id : null, selected != null ? selected.label : null);
    }

    private static final class Agg {
        int sales;
        BigDecimal invoiced = BigDecimal.ZERO, paid = BigDecimal.ZERO, commodityKg = BigDecimal.ZERO;
    }

    /** Le poids accepté, base de facturation ; à défaut le poids déclaré au départ. */
    private static BigDecimal acceptedKg(CommoditySaleEntity cs) {
        if (cs.weights == null) return BigDecimal.ZERO;
        return cs.weights.acceptedKg != null ? cs.weights.acceptedKg : nz(cs.weights.declaredKg);
    }

    private static boolean inScope(UUID candidate, UUID wanted) {
        return wanted == null || wanted.equals(candidate);
    }

    private static String labelOf(Map<UUID, CampaignEntity> byCampaign, UUID id) {
        CampaignEntity c = id == null ? null : byCampaign.get(id);
        return c == null ? null : c.label;
    }

    private static LocalDate startOf(Map<UUID, CampaignEntity> byCampaign, UUID id) {
        CampaignEntity c = id == null ? null : byCampaign.get(id);
        return c == null || c.startDate == null ? LocalDate.MIN : c.startDate;
    }

    private static <T> BigDecimal sum(List<T> items, java.util.function.Function<T, BigDecimal> f) {
        BigDecimal total = BigDecimal.ZERO;
        for (T t : items) total = total.add(nz(f.apply(t)));
        return total;
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
