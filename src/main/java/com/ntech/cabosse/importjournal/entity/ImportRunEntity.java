package com.ntech.cabosse.importjournal.entity;

import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Ce qu'un import a réellement fait, gardé pour pouvoir le relire.
 *
 * <p>Écrit le 15/09/2026 après la revue des dix-neuf imports du produit.
 * La plupart des anomalies relevées ne sont pas des erreurs bruyantes mais
 * des silences : une ligne écartée sans compteur, une colonne lue à la
 * place d'une autre, une valeur par défaut posée sans le dire. Rien, dans
 * la base, ne permettait ensuite de reconstituer ce qui s'était passé :
 * l'écran affichait un compte rendu puis on fermait la page.</p>
 *
 * <p>Une ligne par import, avec ses compteurs, ses lignes refusées et leur
 * motif. C'est un <strong>constat</strong>, au même titre qu'un bordereau
 * de réception : il n'entre dans aucun calcul, ne conditionne aucune
 * écriture, et son absence ne change rien à ce qui a été importé. Cette
 * neutralité est la condition pour l'ajouter sans risque sur des chemins
 * qui écrivent du stock et de l'argent.</p>
 *
 * <p>Il vit dans la base de la structure, avec ses données : un import se
 * relit dans le contexte où il a eu lieu, et l'administration de la
 * plateforme y accède comme au reste, en ouvrant la base du tenant.</p>
 */
public class ImportRunEntity {

    public static final String COLLECTION = "import_runs";

    @BsonId
    public UUID id;

    /**
     * Le domaine importé, en code stable : {@code intake-notes},
     * {@code producer-purchases}, {@code members}… Il sert à retrouver
     * tous les imports d'une même nature, donc il ne suit pas les
     * renommages d'écran.
     */
    public String domain;

    /**
     * L'étape : {@code PREVIEW} ou {@code COMMIT}. Un aperçu qui refuse
     * des lignes explique un commit qui en crée moins que prévu, et les
     * deux méritent d'être conservés.
     */
    public String phase;

    public Instant at;
    public String actorEmail;

    /** Le magasin ou le site choisi à l'import, quand il y en a un. */
    public UUID siteId;

    /** Nom du fichier déposé, quand l'écran le transmet. */
    public String fileName;

    /** Lignes reçues par le serveur, avant tout traitement. */
    public int rowsReceived;
    /** Lignes qui ont produit une écriture. */
    public int rowsCreated;
    /** Lignes reconnues comme déjà présentes, donc volontairement ignorées. */
    public int rowsSkipped;
    /** Lignes refusées, détaillées dans {@link #rejections}. */
    public int rowsRejected;

    /** Durée du traitement, pour repérer un import qui s'enlise. */
    public long durationMs;

    /**
     * Le détail des refus, borné.
     *
     * <p>Un fichier entièrement refusé produirait autant de lignes que le
     * fichier lui-même : on en garde un échantillon, et le compteur dit
     * combien il y en avait. Mieux vaut cent motifs lisibles qu'un
     * document de dix mille lignes que personne n'ouvre.</p>
     */
    public List<ImportRejection> rejections;

    /**
     * Ce que l'import a décidé sans le dire : valeur par défaut posée,
     * référentiel créé au passage, rapprochement approximatif retenu.
     *
     * <p>C'est la moitié utile du journal. Les refus se voient déjà à
     * l'écran ; ces décisions-là ne se voient nulle part, et ce sont
     * elles qui produisent une donnée fausse que personne ne cherche.</p>
     */
    public List<ImportDecision> decisions;

    public Instant createdAt;
}
