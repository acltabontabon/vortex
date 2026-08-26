package com.acltabontabon.vortex.dynatrace;

import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpTransportException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

/**
 * Turns an MCP SDK exception into the failure category a person can act on.
 *
 * <p>Mirrors {@code ObservationHttp.classify} for the REST adapter: the distinctions matter because
 * the remedies differ completely, and collapsing them into "could not connect" would leave a VPN
 * outage looking identical to a rejected token.
 */
final class DynatraceMcpFailureClassifier {

    private DynatraceMcpFailureClassifier() {
    }

    static DynatraceMcpFailureCategory classify(RuntimeException failure) {
        if (failure instanceof McpHttpClientTransportAuthorizationException) {
            return DynatraceMcpFailureCategory.AUTHENTICATION_FAILED;
        }
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof ConnectException || cause instanceof UnknownHostException) {
                return DynatraceMcpFailureCategory.CONNECTION_FAILED;
            }
            if (cause instanceof TimeoutException) {
                return DynatraceMcpFailureCategory.QUERY_TIMEOUT;
            }
            cause = cause.getCause();
        }
        if (failure instanceof McpTransportException) {
            String message = failure.getMessage() == null ? "" : failure.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (message.contains("not found") || message.contains("404")) {
                return DynatraceMcpFailureCategory.SERVICE_NOT_FOUND;
            }
            return DynatraceMcpFailureCategory.CONNECTION_FAILED;
        }
        if (failure instanceof McpError) {
            String message = failure.getMessage() == null ? "" : failure.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (message.contains("timeout") || message.contains("timed out")) {
                return DynatraceMcpFailureCategory.QUERY_TIMEOUT;
            }
            if (message.contains("permission") || message.contains("forbidden")) {
                return DynatraceMcpFailureCategory.PERMISSION_DENIED;
            }
            if (message.contains("unknown tool") || message.contains("no such tool")
                    || message.contains("tool not found")) {
                return DynatraceMcpFailureCategory.MCP_TOOL_UNAVAILABLE;
            }
            return DynatraceMcpFailureCategory.QUERY_REJECTED;
        }
        return DynatraceMcpFailureCategory.INVALID_RESPONSE;
    }
}
