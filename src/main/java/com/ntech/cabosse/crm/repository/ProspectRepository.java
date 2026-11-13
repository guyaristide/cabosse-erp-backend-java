package com.ntech.cabosse.crm.repository;

import com.ntech.cabosse.crm.entity.ProspectEntity;
import com.ntech.cabosse.crm.entity.ProspectStage;
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.Document;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Les prospects, dans le plan de contrôle. */
@ApplicationScoped
public class ProspectRepository implements PanacheMongoRepositoryBase<ProspectEntity, UUID> {

    /** Le dernier touché en tête : c'est celui dont on parle. */
    private static final Document LAST_TOUCHED_FIRST = new Document("updatedAt", -1);

    public List<ProspectEntity> search(ProspectStage stage, String q, int page, int perPage) {
        return find(filter(stage, q), LAST_TOUCHED_FIRST).page(Page.of(page, perPage)).list();
    }

    public long countSearch(ProspectStage stage, String q) {
        return count(filter(stage, q));
    }

    /** Combien de prospects à chaque étape, pour lire le pipeline d'un coup. */
    public Map<ProspectStage, Long> countByStage() {
        return streamAll().collect(Collectors.groupingBy(p -> p.stage, Collectors.counting()));
    }

    /** Les prospects encore ouverts dont l'action prévue est passée ou due aujourd'hui. */
    public long countDue(LocalDate today) {
        return count(new Document("stage", new Document("$in", List.of(
                ProspectStage.CONTACTED.name(), ProspectStage.DEMO.name(),
                ProspectStage.PROPOSAL.name(), ProspectStage.NEGOTIATION.name())))
                .append("nextActionAt", new Document("$lte", today)));
    }

    private static Document filter(ProspectStage stage, String q) {
        Document d = new Document();
        if (stage != null) d.append("stage", stage.name());
        if (q != null && !q.isBlank()) {
            Pattern p = Pattern.compile(Pattern.quote(q.trim()), Pattern.CASE_INSENSITIVE);
            d.append("$or", List.of(
                    new Document("name", p),
                    new Document("city", p),
                    new Document("industry", p),
                    new Document("contacts.name", p)));
        }
        return d;
    }
}
