package com.ntech.cabosse.shared.exception;

import com.ntech.cabosse.shared.api.ApiResponse;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Mappe {@link BusinessException} (et ses sous-classes, dont
 * {@link ValidationException}) vers {@code 422 Unprocessable Entity}
 * avec une enveloppe {@link ApiResponse} cohérente.
 *
 * <p>Les contrôleurs ne doivent <strong>jamais</strong> attraper une
 * exception métier pour produire une réponse à la main : la centralisation
 * via ce mapper garantit que tous les endpoints retournent la même forme
 * (cf. CLAUDE.md §8.3).</p>
 */
@Provider
public class BusinessExceptionMapper implements ExceptionMapper<BusinessException> {

    @Override
    public Response toResponse(BusinessException ex) {
        return Response
                .status(422)
                .entity(new ApiResponse<>(422, ex.getMessage(), null))
                .build();
    }
}
