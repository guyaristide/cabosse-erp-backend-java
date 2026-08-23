package com.ntech.cabosse.shared.exception;

import com.ntech.cabosse.shared.api.ApiResponse;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Mappe {@link ForbiddenException} → {@code 403 Forbidden}. */
@Provider
public class ForbiddenExceptionMapper implements ExceptionMapper<ForbiddenException> {

    @Override
    public Response toResponse(ForbiddenException ex) {
        return Response
                .status(403)
                .entity(ApiResponse.error(403, ex.getMessage(), ErrorCode.FORBIDDEN))
                .build();
    }
}
