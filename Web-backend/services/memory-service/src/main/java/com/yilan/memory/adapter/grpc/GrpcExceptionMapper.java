package com.yilan.memory.adapter.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/** Maps transport-visible failures without exposing authority exception text. */
public final class GrpcExceptionMapper {

    private GrpcExceptionMapper() {
    }

    public static StatusRuntimeException toStatus(RuntimeException error) {
        if (error instanceof StatusRuntimeException statusRuntimeException) {
            return statusRuntimeException;
        }
        return Status.UNAVAILABLE
                .withDescription("memory authority unavailable")
                .asRuntimeException();
    }

    static StatusRuntimeException invalidCapabilityRequest(String diagnosticCode) {
        var description = "SCHEMA_INCOMPATIBLE".equals(diagnosticCode)
                ? "capability schema incompatible"
                : "invalid capability request";
        return Status.INVALID_ARGUMENT.withDescription(description).asRuntimeException();
    }
}
