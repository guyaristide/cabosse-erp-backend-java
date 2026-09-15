package com.ntech.cabosse.importjournal.service;

import com.ntech.cabosse.importjournal.entity.ImportDecision;
import com.ntech.cabosse.importjournal.entity.ImportRejection;
import com.ntech.cabosse.importjournal.entity.ImportRunEntity;
import com.ntech.cabosse.importjournal.repository.ImportRunRepository;
import com.ntech.cabosse.shared.persistence.IdGenerator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Consigne ce qu'un import a fait, sans jamais peser sur lui.
 *
 * <p>Deux règles gouvernent cette classe, et elles priment sur toute
 * commodité d'écriture.</p>
 *
 * <p><strong>Rien de ce qui se passe ici ne peut faire échouer un
 * import.</strong> Le journal s'écrit après coup, hors du chemin qui
 * produit le stock, la dette et les écritures. Toute défaillance est
 * avalée et tracée dans les logs du serveur. Un import réussi dont le
 * journal n'est pas parti reste un import réussi : l'inverse ferait
 * perdre une journée de collecte pour une ligne d'observation.</p>
 *
 * <p><strong>Le journal ne conditionne rien.</strong> Aucune lecture
 * métier ne s'appuie dessus, aucun état ne s'en déduit. C'est un constat,
 * comme le bordereau de réception l'est de la matière descendue. Cette
 * neutralité est ce qui permet de l'ajouter sur des chemins déjà en
 * production sans rien y risquer.</p>
 *
 * <p>Usage : ouvrir une trace au début du traitement, y déposer les refus
 * et les décisions au fil de l'eau, la clore à la fin. La trace est un
 * objet local à l'appel, donc sans partage entre requêtes concurrentes.</p>
 */
@ApplicationScoped
public class ImportJournal {

    private static final Logger LOG = Logger.getLogger(ImportJournal.class);

    /**
     * Au-delà, on ne lit plus les refus, on les subit. Le compteur, lui,
     * reste exact : c'est lui qui dit l'ampleur.
     */
    static final int MAX_REJECTIONS = 200;

    @Inject ImportRunRepository repo;
    @Inject IdGenerator idGenerator;
    @Inject JsonWebToken jwt;

    /** Ouvre une trace. Ne touche pas la base tant qu'elle n'est pas close. */
    public Trace open(String domain, String phase) {
        return new Trace(domain, phase);
    }

    /**
     * La trace d'un import en cours.
     *
     * <p>Les décisions sont regroupées par nature, champ et valeur
     * retenue : un fichier de six cents lignes où le mode de paiement est
     * vide ne produit pas six cents observations, mais une seule qui dit
     * six cents. C'est ce regroupement qui rend le journal lisible.</p>
     */
    public final class Trace {

        private final String domain;
        private final String phase;
        private final long startedAt = System.currentTimeMillis();
        private final List<ImportRejection> rejections = new ArrayList<>();
        private final Map<String, ImportDecision> decisions = new LinkedHashMap<>();

        private UUID siteId;
        private String fileName;
        private int received;
        private int created;
        private int skipped;
        private int rejected;

        private Trace(String domain, String phase) {
            this.domain = domain;
            this.phase = phase;
        }

        public Trace site(UUID siteId) {
            this.siteId = siteId;
            return this;
        }

        public Trace file(String fileName) {
            this.fileName = fileName;
            return this;
        }

        public Trace received(int rows) {
            this.received = rows;
            return this;
        }

        public Trace counts(int created, int skipped) {
            this.created = created;
            this.skipped = skipped;
            return this;
        }

        /** Une ligne refusée. Le compteur monte même au-delà de l'échantillon. */
        public Trace rejected(int rowNumber, String reason, String rowHint) {
            rejected++;
            if (rejections.size() < MAX_REJECTIONS) {
                rejections.add(new ImportRejection(rowNumber, reason, rowHint));
            }
            return this;
        }

        /**
         * Une décision prise à la place de l'opérateur.
         *
         * @param kind    {@code DEFAULT_APPLIED}, {@code FUZZY_MATCH},
         *                {@code REFERENCE_CREATED} ou {@code LEFT_NULL}
         * @param field   le champ, tel qu'il s'appelle dans le fichier
         * @param given   ce que le fichier disait, ou {@code null}
         * @param applied ce qui a été retenu
         */
        public Trace decided(String kind, String field, String given, String applied) {
            String key = kind + "|" + field + "|" + applied;
            ImportDecision d = decisions.get(key);
            if (d == null) {
                decisions.put(key, new ImportDecision(kind, field, given, applied, 1));
            } else {
                d.occurrences++;
            }
            return this;
        }

        /**
         * Clôt la trace et l'écrit.
         *
         * <p>N'échoue jamais : c'est tout l'intérêt de l'appeler depuis un
         * service qui vient d'écrire du stock et de l'argent.</p>
         */
        public void close() {
            try {
                ImportRunEntity e = new ImportRunEntity();
                e.id = idGenerator.newId();
                e.domain = domain;
                e.phase = phase;
                e.at = Instant.now();
                e.actorEmail = actor();
                e.siteId = siteId;
                e.fileName = fileName;
                e.rowsReceived = received;
                e.rowsCreated = created;
                e.rowsSkipped = skipped;
                e.rowsRejected = rejected;
                e.durationMs = System.currentTimeMillis() - startedAt;
                e.rejections = List.copyOf(rejections);
                e.decisions = List.copyOf(decisions.values());
                e.createdAt = e.at;
                repo.insert(e);
            } catch (RuntimeException ex) {
                LOG.warnf(ex, "Trace d'import non écrite pour le domaine %s", domain);
            }
        }
    }

    private String actor() {
        try {
            return jwt.getName();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
