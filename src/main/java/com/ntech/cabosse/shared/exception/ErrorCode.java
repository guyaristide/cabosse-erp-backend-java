package com.ntech.cabosse.shared.exception;

/**
 * Motif d'échec exploitable par une machine.
 *
 * <p>Jusqu'ici, une erreur était une phrase en français. Un humain la lit
 * très bien ; une file de rejeu hors ligne, non : elle doit décider seule
 * s'il faut réessayer plus tard, abandonner définitivement, ou considérer
 * que l'opération est déjà passée. Sans ce code, elle ne peut que
 * réessayer indéfiniment ou tout jeter.</p>
 *
 * <p>{@link #retryable()} est la seule information dont la file a besoin
 * pour trancher : un refus métier ne deviendra pas vrai en le répétant,
 * alors qu'une panne passagère se répare toute seule. Le message en
 * français reste, il s'adresse à l'utilisateur.</p>
 */
public enum ErrorCode {

    // ─── Refus métier : inutile de réessayer tel quel ───

    /** Règle métier violée, sans code plus précis. */
    BUSINESS_RULE(false),
    /** Sortie supérieure au stock disponible. */
    STOCK_INSUFFICIENT(false),
    /** Écriture dans une période comptable verrouillée. */
    PERIOD_LOCKED(false),
    /** Numéro de reçu officiel déjà utilisé par une autre opération. */
    DUPLICATE_RECEIPT(false),
    /** Crédit ou avance insuffisant pour l'imputation demandée. */
    CREDIT_INSUFFICIENT(false),
    /** Plafond du plan tarifaire atteint (sites, comptes, membres). */
    PLAN_LIMIT(false),
    /** Dossier producteur incomplet ou pièce expirée. */
    PRODUCER_FILE_INCOMPLETE(false),
    /** Module non activé pour ce tenant. */
    CAPABILITY_REQUIRED(false),
    /** Données du formulaire invalides. */
    VALIDATION(false),
    /** Ressource inexistante. */
    NOT_FOUND(false),
    /** Droits insuffisants. */
    FORBIDDEN(false),
    /** Non authentifié ou session expirée. */
    UNAUTHORIZED(false),
    /** Conflit d'état (doublon, transition déjà effectuée). */
    CONFLICT(false),
    /**
     * Même clé d'idempotence présentée avec un contenu différent. Rejouer
     * n'y changera rien : c'est un défaut de l'appelant, qui a réutilisé
     * une clé pour une autre opération.
     */
    IDEMPOTENCY_PAYLOAD_MISMATCH(false),

    // ─── Incidents passagers : réessayer a du sens ───

    /** Panne inattendue côté serveur. */
    INTERNAL(true),
    /** Ressource momentanément verrouillée par une autre opération. */
    CONCURRENT_UPDATE(true);

    private final boolean retryable;

    ErrorCode(boolean retryable) {
        this.retryable = retryable;
    }

    /** Réessayer plus tard peut-il aboutir ? */
    public boolean retryable() {
        return retryable;
    }
}
