package com.ntech.cabosse.shared.exception;

/**
 * Règle métier violée. Mappée vers {@code HTTP 422 Unprocessable Entity}
 * par {@link com.ntech.cabosse.shared.exception.BusinessExceptionMapper}.
 *
 * <p>Distincte de {@link ValidationException} (qui couvre les contraintes
 * Bean Validation côté DTO) : ici, on parle de logique applicative
 * (état incohérent, transition interdite, conflit métier…).</p>
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(String message) {
        this(ErrorCode.BUSINESS_RULE, message);
    }

    /**
     * Refus métier qualifié. Le code permet à un appelant automatique
     * (file de rejeu hors ligne) de savoir s'il doit abandonner ou
     * réessayer, là où le message français ne s'adresse qu'à l'humain.
     *
     * <p>Le code vient en premier parce que le message court souvent sur
     * plusieurs lignes : le lire d'abord évite de chercher la
     * qualification en fin d'appel.</p>
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode != null ? errorCode : ErrorCode.BUSINESS_RULE;
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = ErrorCode.BUSINESS_RULE;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
