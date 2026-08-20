package com.seuprojeto.backend.repository;

/**
 * One row of the organizer dashboard aggregate.
 *
 * <p>The alias is {@code groupKey}, not {@code key}: {@code KEY} is a reserved word in JPQL (the
 * map-key function) and using it as an alias fails to parse.
 */
public interface InterestSummaryRow {

    String getGroupKey();

    long getRetrievalCount();

    double getAvgScore();

    long getDistinctSessions();
}
