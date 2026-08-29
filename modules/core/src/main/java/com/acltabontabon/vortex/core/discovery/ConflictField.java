package com.acltabontabon.vortex.core.discovery;

/** Which part of a service's existing configuration a discovered value disagrees with. */
public enum ConflictField {
    OPENAPI_SOURCE,
    EXECUTION_TARGET,
    LOCAL_LAB
}
