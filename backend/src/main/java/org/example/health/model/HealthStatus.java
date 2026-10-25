package org.example.health.model;

/**
 * Overall health state of the service.
 */
public enum HealthStatus {

    /** The dependency (PostgreSQL) answered successfully. */
    UP,

    /** The dependency (PostgreSQL) is not reachable or did not answer correctly. */
    DOWN
}