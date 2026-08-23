package com.ntech.cabosse.shared.exception;

import com.ntech.cabosse.shared.api.ApiResponse;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Mappe {@link NotFoundException} → {@code 404 Not Found}.
 *
 * <p>Notre exception métier {@code NotFoundException} (package
 * {@code com.ntech.cabosse.shared.exception}) est volontairement
 * distincte de la {@code jakarta.ws.rs.NotFoundException} de JAX-RS,
 * qui se déclenche pour des chemins inexistants — les deux sont mappées
 * vers 404 mais avec des sémantiques différentes.</p>
 */
@Provider
public class NotFoundExceptionMapper implements ExceptionMapper<NotFoundException> {

    @Override
    public Response toResponse(NotFoundException ex) {
        return Response
                .status(404)
                .entity(ApiResponse.error(404, ex.getMessage(), ErrorCode.NOT_FOUND))
                .build();
    }
}
