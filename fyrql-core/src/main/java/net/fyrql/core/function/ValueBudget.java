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

package net.fyrql.core.function;

/**
 * The memory that functions take for the large values they build, such as
 * the lists of {@code range} and {@code generate_series}: the operator that
 * computes a batch on the current thread gives an account, which counts the
 * bytes against the memory budget of its query while it computes the batch,
 * so that such a value fails the query rather than exhaust the heap.
 * Without an account, nothing is counted.
 */
public final class ValueBudget {

    /** Takes bytes for values. */
    public interface Account {

        /**
         * Takes bytes for a value that a function is about to build.
         *
         * @param what the function, for the message
         * @throws RuntimeException if the bytes are not left
         */
        void take(long bytes, String what);
    }

    private static final ThreadLocal<Account> ACCOUNT = new ThreadLocal<>();

    private ValueBudget() {
    }

    /**
     * Gives the account of the current thread until {@link #leave}.
     *
     * @return the account before, to give back to {@link #leave}
     */
    public static Account enter(Account account) {
        Account previous = ACCOUNT.get();
        ACCOUNT.set(account);
        return previous;
    }

    /** Gives back the account the thread had before {@link #enter}. */
    public static void leave(Account previous) {
        if (previous == null) {
            ACCOUNT.remove();
        } else {
            ACCOUNT.set(previous);
        }
    }

    /**
     * Takes bytes from the account of the current thread, if it has one.
     *
     * @throws RuntimeException if the bytes are not left
     */
    public static void take(long bytes, String what) {
        Account account = ACCOUNT.get();
        if (account != null) {
            account.take(bytes, what);
        }
    }
}
