package com.ntech.cabosse.accounting.service;

import com.ntech.cabosse.accounting.dto.StockReconciliationRowDto;
import com.ntech.cabosse.accounting.entity.SyscohadaAccounts;
import com.ntech.cabosse.accounting.repository.JournalPieceRepository;
import com.ntech.cabosse.stock.entity.StockItemEntity;
import com.ntech.cabosse.stock.repository.StockItemRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ce que le magasin vaut, en face de ce que le journal en dit.
 *
 * <p>Le stock de départ reste un constat de quantité : il ne passe
 * aucune écriture, et c'est le comptable qui saisit ses à-nouveaux
 * (décision de l'expert du 12/10/2026). Les deux chiffres vivaient donc
 * côte à côte sans que rien ne les rapproche : une structure qui amorce
 * cinquante tonnes puis déclare une perte de brassage voyait son compte
 * de stock devenir créditeur, et son bilan afficher un actif négatif,
 * sans que rien ne dise d'où cela venait.</p>
 *
 * <p>L'état ne corrige rien et ne bloque rien : il montre l'écart, et
 * c'est le comptable qui décide ce qu'il en fait.</p>
 */
@ApplicationScoped
public class StockReconciliationService {

    @Inject StockItemRepository stockItems;
    @Inject JournalPieceRepository pieces;

    public List<StockReconciliationRowDto> rows() {
        // La valeur du magasin, groupée par le compte que sa nature
        // mouvemente : c'est la maille à laquelle le journal répond.
        Map<String, BigDecimal> valueByAccount = new LinkedHashMap<>();
        for (StockItemEntity item : stockItems.listAll()) {
            String account = SyscohadaAccounts.stockAccountFor(item.articleType);
            // Le transport ne se stocke pas : il n'a pas de compte.
            if (account == null) continue;
            BigDecimal value = nz(item.quantity).multiply(nz(item.cmup))
                    .setScale(2, RoundingMode.HALF_UP);
            valueByAccount.merge(account, value, BigDecimal::add);
        }

        // Un compte peut porter un solde sans qu'aucun article ne lui
        // réponde : le comptable a saisi ses à-nouveaux avant que le
        // magasin n'ait amorcé. L'écart est alors en sens inverse, et il
        // se lit tout autant.
        for (String account : SyscohadaAccounts.stockAccounts()) {
            valueByAccount.putIfAbsent(account, BigDecimal.ZERO);
        }

        List<StockReconciliationRowDto> rows = new ArrayList<>(valueByAccount.size());
        for (Map.Entry<String, BigDecimal> e : valueByAccount.entrySet()) {
            BigDecimal ledger = pieces.balance(e.getKey(), null);
            BigDecimal value = e.getValue();
            if (value.signum() == 0 && ledger.signum() == 0) continue;
            rows.add(new StockReconciliationRowDto(
                    e.getKey(), value, ledger, value.subtract(ledger)));
        }
        rows.sort((a, b) -> a.account().compareTo(b.account()));
        return rows;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
