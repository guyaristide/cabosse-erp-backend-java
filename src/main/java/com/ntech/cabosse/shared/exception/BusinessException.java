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

    public BusinessException(String message) {
        super(message);
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
    }
}
