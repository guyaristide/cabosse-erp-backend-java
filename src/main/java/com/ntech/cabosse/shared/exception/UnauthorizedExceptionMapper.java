package com.ntech.cabosse.shared.exception;

import com.ntech.cabosse.shared.api.ApiResponse;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Mappe {@link UnauthorizedException} → {@code 401 Unauthorized}. */
@Provider
public class UnauthorizedExceptionMapper implements ExceptionMapper<UnauthorizedException> {

    @Override
    public Response toResponse(UnauthorizedException ex) {
        return Response
                .status(401)
                .entity(new ApiResponse<>(401, ex.getMessage(), null))
                .build();
    }
}
