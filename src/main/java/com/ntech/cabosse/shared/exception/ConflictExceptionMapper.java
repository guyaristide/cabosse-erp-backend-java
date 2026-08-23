package com.ntech.cabosse.shared.exception;

import com.ntech.cabosse.shared.api.ApiResponse;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Mappe {@link ConflictException} → {@code 409 Conflict}. */
@Provider
public class ConflictExceptionMapper implements ExceptionMapper<ConflictException> {

    @Override
    public Response toResponse(ConflictException ex) {
        return Response
                .status(409)
                .entity(ApiResponse.error(409, ex.getMessage(), ex.errorCode()))
                .build();
    }
}
