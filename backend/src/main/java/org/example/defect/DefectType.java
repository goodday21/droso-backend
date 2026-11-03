package org.example.defect;

/**
 * Kind of a {@link Defect}: a missing capability or a fault of an existing one. Stored by its name
 * in {@code defects.type}; a new value needs a migration replacing {@code defects_type_check}.
 */
public enum DefectType {
    FEATURE,
    BUG
}
