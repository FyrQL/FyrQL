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

package net.fyrql.sql.ast;

import java.util.List;
import java.util.Objects;

/**
 * The window of a window function, as written after {@code OVER}: the name
 * of a window of the {@code WINDOW} clause, or {@code PARTITION BY},
 * {@code ORDER BY} and a frame, each of which may be absent. A window that
 * names another takes nothing else, since the parser reads no more.
 *
 * @param base the name of the window this one refers to, or {@code null}
 * @param frame the frame, or {@code null} for the default frame
 */
public record WindowSpec(String base, List<SqlExpr> partitionBy, List<OrderItem> orderBy, Frame frame) {

    public WindowSpec {
        partitionBy = List.copyOf(partitionBy);
        orderBy = List.copyOf(orderBy);
        for (OrderItem item : orderBy) {
            if (item.isAll()) {
                throw new IllegalArgumentException("ORDER BY ALL in a window");
            }
        }
        if (base != null && (!partitionBy.isEmpty() || !orderBy.isEmpty() || frame != null)) {
            throw new IllegalArgumentException("a window that names another takes nothing else");
        }
    }

    /** {@code OVER name}: the window of that name. */
    public static WindowSpec named(String base) {
        return new WindowSpec(Objects.requireNonNull(base, "base"), List.of(), List.of(), null);
    }

    /** The units in which a frame counts. */
    public enum FrameUnit {
        ROWS, RANGE, GROUPS
    }

    /** The kinds of the bounds of a frame. */
    public enum BoundKind {
        UNBOUNDED_PRECEDING("UNBOUNDED PRECEDING"), PRECEDING("PRECEDING"), CURRENT_ROW("CURRENT ROW"),
        FOLLOWING("FOLLOWING"), UNBOUNDED_FOLLOWING("UNBOUNDED FOLLOWING");

        private final String keywords;

        BoundKind(String keywords) {
            this.keywords = keywords;
        }

        /** Returns the keywords of the bound, after the offset if it has one. */
        public String keywords() {
            return keywords;
        }

        /** Tells whether a bound of this kind has an offset. */
        public boolean hasOffset() {
            return this == PRECEDING || this == FOLLOWING;
        }
    }

    /** The rows that {@code EXCLUDE} takes out of a frame; {@link #NO_OTHERS} takes none. */
    public enum Exclusion {
        NO_OTHERS("NO OTHERS"), CURRENT_ROW("CURRENT ROW"), GROUP("GROUP"), TIES("TIES");

        private final String keywords;

        Exclusion(String keywords) {
            this.keywords = keywords;
        }

        /** Returns the keywords after {@code EXCLUDE}. */
        public String keywords() {
            return keywords;
        }
    }

    /** One bound of a frame; the offset is there exactly for {@code n PRECEDING} and {@code n FOLLOWING}. */
    public record Bound(BoundKind kind, SqlExpr offset) {
        public Bound {
            Objects.requireNonNull(kind, "kind");
            if (kind.hasOffset() != (offset != null)) {
                throw new IllegalArgumentException(kind.hasOffset() ? kind + " needs an offset"
                        : kind + " takes no offset");
            }
        }

        /** A bound without an offset. */
        public static Bound of(BoundKind kind) {
            return new Bound(kind, null);
        }
    }

    /**
     * {@code unit BETWEEN start AND end [EXCLUDE ...]}; a frame of one bound
     * is written with {@code CURRENT ROW} as its end.
     */
    public record Frame(FrameUnit unit, Bound start, Bound end, Exclusion exclusion) {
        public Frame {
            Objects.requireNonNull(unit, "unit");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            Objects.requireNonNull(exclusion, "exclusion");
        }
    }
}
