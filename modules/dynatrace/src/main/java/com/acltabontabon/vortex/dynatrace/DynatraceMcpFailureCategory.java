package com.acltabontabon.vortex.dynatrace;

/**
 * Why a Dynatrace MCP call did not answer, classified into the categories a person can act on.
 *
 * <p>Never surfaced as "it did not work" — each category maps to a specific remedy in
 * {@link DynatraceMcpConnectionTest} and {@link DynatraceMcpObservationSource}.
 */
public enum DynatraceMcpFailureCategory {
    CONNECTION_FAILED,
    AUTHENTICATION_FAILED,
    PERMISSION_DENIED,
    QUERY_REJECTED,
    QUERY_TIMEOUT,
    INVALID_RESPONSE,
    SERVICE_NOT_FOUND,
    NO_DATA,
    MCP_TOOL_UNAVAILABLE
}
