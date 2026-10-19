/*
 * Copyright 2026 the FyrQL authors
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * This Source Code Form is "Incompatible With Secondary Licenses", as
 * defined by the Mozilla Public License, v. 2.0.
 */

package net.fyrql.test.random;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * One run of random queries: counts the outcomes, explains differences
 * through the {@link Ledger}, and keeps the unexplained ones, shrunk, for
 * the report. The number of queries and the seed come from the system
 * properties {@code fyrql.random.queries} (200 by default) and
 * {@code fyrql.random.seed}; {@code fyrql.random.shrunk} sets how many
 * unexplained differences are shrunk for the report (5 by default).
 */
final class RandomRun {

    static final int QUERIES = Integer.getInteger("fyrql.random.queries", 200);
    static final long SEED = Long.getLong("fyrql.random.seed", 20261014L);

    /** Unexplained differences beyond this number are counted but not shrunk; {@code fyrql.random.shrunk}. */
    private static final int SHRUNK = Integer.getInteger("fyrql.random.shrunk", 5);

    private final String title;
    private final Ledger ledger;
    private final Map<Outcome.Kind, Integer> kinds = new EnumMap<>(Outcome.Kind.class);
    private final List<String> unexplained = new ArrayList<>();
    private int explained;
    private int checks;

    RandomRun(String title, String duckVersion) {
        this.title = title;
        this.ledger = new Ledger(duckVersion);
    }

    Ledger ledger() {
        return ledger;
    }

    /** The random source of one query, from the seed of the run and the number of the query. */
    static java.util.SplittableRandom random(int query) {
        return new java.util.SplittableRandom(SEED * 1_000_003L + query);
    }

    /**
     * Records the outcome of one check.
     *
     * @param what what was checked, for the report
     * @param shrink describes the difference after shrinking the query, called only for an unexplained one
     */
    void record(int query, String what, Outcome outcome, Supplier<String> shrink) {
        checks++;
        kinds.merge(outcome.kind(), 1, Integer::sum);
        if (!outcome.kind().differs()) {
            return;
        }
        Ledger.Entry entry = ledger.explain(outcome);
        if (entry != null) {
            explained++;
            ledger.count(entry);
            return;
        }
        String head = "query " + query + " (" + what + "): " + outcome.describe() + "\n  sql: " + outcome.sql();
        unexplained.add(unexplained.size() < SHRUNK ? head + "\n" + shrink.get().indent(2).stripTrailing() : head);
    }

    List<String> unexplained() {
        return unexplained;
    }

    /** The report: the outcomes by kind, the hits of the ledger, and the unexplained differences. */
    String report() {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append(": ").append(QUERIES).append(" queries, seed ").append(SEED).append(", ")
                .append(checks).append(" checks\n");
        sb.append("  outcomes: ").append(kinds).append('\n');
        sb.append("  explained by the ledger: ").append(explained).append(' ').append(ledger.hits()).append('\n');
        sb.append("  unexplained: ").append(unexplained.size()).append('\n');
        for (String u : unexplained) {
            sb.append('\n').append(u.indent(2));
        }
        return sb.toString();
    }
}
