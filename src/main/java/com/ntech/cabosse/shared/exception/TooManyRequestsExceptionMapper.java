package com.ntech.cabosse.shared.exception;

import com.ntech.cabosse.shared.api.ApiResponse;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Mappe {@link TooManyRequestsException} → {@code 429 Too Many Requests}. */
@Provider
public class TooManyRequestsExceptionMapper
        implements ExceptionMapper<TooManyRequestsException> {

    @Override
    public Response toResponse(TooManyRequestsException ex) {
        // En-tête standard : les clients et les proxys savent le lire,
        // et il évite de réessayer en boucle pendant le refus.
        long seconds = Math.max(1, ex.retryAfter().toSeconds());
        return Response
                .status(429)
                .header("Retry-After", seconds)
                .entity(ApiResponse.error(429, ex.getMessage(), ErrorCode.TOO_MANY_REQUESTS))
                .build();
    }
}
