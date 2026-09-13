package com.ntech.cabosse.stock.service;

import com.ntech.cabosse.intake.entity.IntakeNoteEntity;
import com.ntech.cabosse.intake.repository.IntakeNoteRepository;
import com.ntech.cabosse.stock.dto.StockMovementResponseDto;
import com.ntech.cabosse.stock.entity.MovementSource;
import com.ntech.cabosse.stock.entity.StockMovementEntity;
import com.ntech.cabosse.stock.repository.StockMovementRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Le journal des mouvements d'un article, tel qu'il se lit.
 *
 * <p>Chaque ligne porte le bordereau de réception d'où la matière vient,
 * quand elle vient d'une livraison. Demandé par l'expert-comptable le
 * 12/09/2026 : l'historique s'identifiait par le nom du producteur, alors
 * qu'une entrée se retrouve par son bordereau et par l'heure à laquelle
 * elle a été enregistrée.</p>
 *
 * <p>Le rattachement se résout à la lecture, en une requête pour toute la
 * page : le mouvement ne connaît que le reçu, et c'est le bordereau qui
 * le revendique.</p>
 */
@ApplicationScoped
public class StockMovementViewService {

    @Inject StockMovementRepository movements;
    @Inject IntakeNoteRepository intakeNotes;

    public List<StockMovementResponseDto> listForArticle(UUID articleId, UUID siteId,
                                                         int limit, int skip) {
        List<StockMovementEntity> page = movements.listByArticleAndSite(articleId, siteId, limit, skip);
        Map<String, String> noteByReceipt = noteRefsFor(page);
        List<StockMovementResponseDto> out = new ArrayList<>(page.size());
        for (StockMovementEntity m : page) {
            out.add(StockMovementResponseDto.from(m, noteByReceipt.get(m.sourceRef)));
        }
        return out;
    }

    private Map<String, String> noteRefsFor(List<StockMovementEntity> page) {
        Set<String> receiptRefs = new HashSet<>();
        for (StockMovementEntity m : page) {
            if (m.sourceType == MovementSource.PRODUCER_PURCHASE && m.sourceRef != null) {
                receiptRefs.add(m.sourceRef);
            }
        }
        Map<String, String> byReceipt = new HashMap<>();
        for (IntakeNoteEntity note : intakeNotes.findByReceiptRefs(receiptRefs)) {
            if (note.receiptRefs == null) continue;
            for (String ref : note.receiptRefs) {
                if (receiptRefs.contains(ref)) byReceipt.put(ref, note.ref);
            }
        }
        return byReceipt;
    }
}
