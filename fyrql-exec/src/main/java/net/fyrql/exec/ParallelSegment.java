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

package net.fyrql.exec;

import net.fyrql.connector.BatchReader;
import net.fyrql.connector.SourceCounters;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.plan.PlanNode.JoinType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.exec.spi.JoinTable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Runs a segment, see {@link SegmentPlanner}. First the build sides of the
 * joins of each chain become {@link JoinTable}s, from the top join down, as
 * the operators would read them: below an empty build side of an
 * {@code INNER} or {@code SEMI} join, nothing is built and the chain reads
 * nothing. Once the splits of the scans of the other chains are known,
 * every split is a {@link Morsel}; with more than one of them and more than
 * one thread, up to {@code threads} workers each run a copy of the chains
 * of the segment over the shared tables, take the morsels one at a time,
 * so that a fast worker takes more of them, and pass their batches through
 * an {@link Exchange}, or each into a sink of its own, see {@link #drain}.
 * Otherwise the chains run on the thread of the query,
 * one after the other, as their operators would without a segment. A
 * {@code RIGHT} or {@code FULL} join at the top of a chain emits the build
 * rows that never matched after all others, once.
 * <p>
 * A failure of a worker ends the segment, with the failure as the
 * operator of the worker reports it, see {@link Exchange}. Closing the
 * segment stops the workers and waits for them; each closes the operators
 * of its copy itself.
 */
final class ParallelSegment extends AbstractOperator {

    /**
     * A chain of the segment, one per input of a union.
     *
     * @param scan      the scan at the bottom of the chain
     * @param scanLabel the label of the scan
     * @param builds    the build sides of the joins of the chain, from the top down
     * @param build     builds the operators of a copy of the chain over a scan and the tables of its joins
     */
    record Chain(PlanNode.Scan scan, String scanLabel, List<Build> builds,
            BiFunction<MorselScan, Map<PlanNode.Join, JoinTable>, Operator> build) {

        /**
         * Returns the join at the top of the chain that emits its unmatched
         * build rows, or {@code null}; such a join is only the top of a chain.
         */
        PlanNode.Join leftovers() {
            return builds.isEmpty() || !SegmentPlanner.keepsUnmatchedRight(builds.get(0).join()) ? null
                    : builds.get(0).join();
        }
    }

    /**
     * The build side of a join of a chain.
     *
     * @param join  the join
     * @param label the label of the join
     * @param right the operator of its right input
     */
    record Build(PlanNode.Join join, String label, Operator right) {
    }

    /**
     * Takes the batches of one worker, or of the thread of the query, see
     * {@link #drain}: those of each morsel in their order, the morsels in
     * the order the worker took them, which is the order of their indexes.
     */
    interface Sink {

        /**
         * Takes a batch of a morsel.
         *
         * @param morsel the index of the morsel, or for the unmatched build
         *               rows at the end, the number of morsels
         */
        void accept(int morsel, Batch b);
    }

    /** Where a worker puts a batch of a morsel; {@code false} if no further batch is needed. */
    private interface Output {
        boolean put(Morsel morsel, Batch b);
    }

    /** What a worker made of a batch of a morsel. */
    private record Entry<T>(int morsel, T value) {
    }

    /** Keeps what a function makes of each batch, with the index of its morsel. */
    private static final class Collector<T> implements Sink {
        final Function<Batch, T> work;
        final List<Entry<T>> entries = new ArrayList<>();

        Collector(Function<Batch, T> work) {
            this.work = work;
        }

        @Override
        public void accept(int morsel, Batch b) {
            entries.add(new Entry<>(morsel, work.apply(b)));
        }
    }

    private final List<Chain> chains;
    private final TableSource source;
    private final int threads;
    private final boolean ordered;
    private final MemoryBudget budget;
    private final Codegen codegen;

    private boolean started;
    private final Map<PlanNode.Join, JoinTable> tables = new IdentityHashMap<>();
    private final AtomicLong reserved = new AtomicLong();
    /** The splits of each chain, {@code null} for a chain that reads nothing; the chains that read something. */
    private final List<Splits> splits = new ArrayList<>();
    private final List<Integer> active = new ArrayList<>();
    private int morselCount;
    /**
     * On the thread of the query: the morsels of each active chain, its
     * operators, the scans of the chains, the chain it reads, and the index
     * of the morsel of the batch it returned last.
     */
    private List<Iterator<Morsel>> local;
    private final List<Operator> operators = new ArrayList<>();
    private final List<MorselScan> localScans = new ArrayList<>();
    private int chain;
    private int lastMorsel;
    /** On workers: the exchange and the tasks. */
    private Exchange exchange;
    private final List<Future<?>> workers = new ArrayList<>();
    /** The next chain whose unmatched build rows are due at the end. */
    private int leftoverChain;

    /**
     * @param chains  the chains, in the order of the inputs of a union
     * @param threads the most workers
     * @param ordered whether the batches come in the order of the morsels, as on one thread
     * @param budget  the budget the build sides are reserved in
     * @param codegen the code generation, for the key functions that hash the build sides
     */
    ParallelSegment(String label, Schema schema, List<Chain> chains, TableSource source, int threads,
            boolean ordered, MemoryBudget budget, Codegen codegen) {
        super(label, schema);
        this.chains = List.copyOf(chains);
        this.source = source;
        this.threads = threads;
        this.ordered = ordered;
        this.budget = budget;
        this.codegen = codegen;
    }

    /** The copies of the chains count, under the same labels as the segment and its scans. */
    @Override
    boolean counts() {
        return false;
    }

    @Override
    Batch compute() {
        if (!started) {
            start(null);
        }
        Batch b = exchange != null ? exchange.take() : local();
        if (b == null && !context().isCancelled()) {
            b = leftovers();
        }
        return b == null ? null : renamed(b);
    }

    /** The next batch of the chains on the thread of the query, or {@code null} at their end. */
    private Batch local() {
        while (chain < active.size()) {
            if (operators.size() == chain) {
                Iterator<Morsel> morsels = local.get(chain);
                operators.add(open(active.get(chain), (ch, finished) -> morsels.hasNext() ? morsels.next() : null));
            }
            Batch b = operators.get(chain).next();
            if (b != null) {
                lastMorsel = localScans.get(chain).morsel().index();
                return b;
            }
            chain++;
        }
        return null;
    }

    /** The next batch of build rows that never matched, of the chains in their order, or {@code null}. */
    private Batch leftovers() {
        while (leftoverChain < chains.size()) {
            PlanNode.Join j = chains.get(leftoverChain++).leftovers();
            Batch b = j == null ? null : tables.get(j).leftovers(j.left().schema(), j.schema());
            if (b != null) {
                lastMorsel = morselCount;
                return b;
            }
        }
        return null;
    }

    private Batch renamed(Batch b) {
        return b.schema().equals(schema()) ? b : new Batch(schema(), b.rowCount(), b.columns());
    }

    /**
     * Runs the segment to its end, each batch given to a function on the
     * worker that made it, and returns the results in the order of the
     * batches, as one thread would make them: the results of each morsel in
     * the order of the morsels. Instead of {@link #next()}.
     *
     * @param work makes the function of a worker, or of the thread of the query
     * @throws RuntimeException the failure of the first morsel that failed
     */
    <T> List<T> collect(Supplier<? extends Function<Batch, T>> work) {
        List<Collector<T>> collectors = drain(sinks -> new Collector<>(work.get()));
        List<List<T>> byMorsel = new ArrayList<>(Collections.nCopies(morselCount + 1, null));
        for (Collector<T> c : collectors) {
            for (Entry<T> e : c.entries) {
                List<T> of = byMorsel.get(e.morsel());
                if (of == null) {
                    of = new ArrayList<>();
                    byMorsel.set(e.morsel(), of);
                }
                of.add(e.value());
            }
        }
        List<T> results = new ArrayList<>();
        for (List<T> of : byMorsel) {
            if (of != null) {
                results.addAll(of);
            }
        }
        return results;
    }

    /**
     * Runs the segment to its end, each batch given to the sink of the
     * worker that made it, and returns the sinks: those of the workers in
     * their order and, if there are build rows that never matched, one more
     * on the thread of the query, which takes them at the end; or a single
     * sink on the thread of the query that takes all batches in their order,
     * if the segment does not run on workers. Instead of {@link #next()}.
     *
     * @param sinks makes a sink, given the number of the workers, or 1 on the thread of the query alone
     * @throws RuntimeException the failure of the first morsel that failed
     */
    <S extends Sink> List<S> drain(IntFunction<S> sinks) {
        if (started) {
            throw new IllegalStateException("The segment " + label() + " is read already");
        }
        List<S> made = new ArrayList<>();
        // The sinks on the workers work for the operator that drains the segment.
        OperatorCounters.Part consumer = context().counting() ? Timing.current().owner() : null;
        Timing.run(context(), null, () -> {
            start((ex, workerCount) -> {
                S sink = sinks.apply(workerCount);
                made.add(sink);
                OperatorCounters.Part worker = consumer == null ? null : consumer.counters().part();
                return (morsel, b) -> {
                    accept(sink, morsel.index(), renamed(b), worker);
                    return ex.wanted(morsel);
                };
            });
            return null;
        });
        if (exchange == null) {
            S sink = sinks.apply(1);
            for (Batch b = next(); b != null; b = next()) {
                sink.accept(lastMorsel, b);
            }
            return List.of(sink);
        }
        if (!Timing.run(context(), null, exchange::await)) {
            checkCancelled();
        }
        S rest = null;
        for (Batch b = leftovers(); b != null; b = leftovers()) {
            if (rest == null) {
                rest = sinks.apply(made.size());
                made.add(rest);
            }
            rest.accept(morselCount, renamed(b));
        }
        return made;
    }

    /** Gives a batch to a sink on a worker, timed for the operator that drains the segment, if any. */
    private static void accept(Sink sink, int morsel, Batch b, OperatorCounters.Part consumer) {
        if (consumer == null) {
            sink.accept(morsel, b);
            return;
        }
        Timing timing = Timing.current();
        long start = timing.begin(consumer);
        try {
            sink.accept(morsel, b);
        } finally {
            timing.end(start);
        }
    }

    /**
     * Builds the tables of the joins, asks for the splits of the scans and
     * starts the workers if there is work for more than one.
     *
     * @param outputs makes the output of a worker over the exchange, given the number of workers, {@code null}
     *                for the exchange itself
     */
    private void start(BiFunction<Exchange, Integer, Output> outputs) {
        started = true;
        List<Morsel> morsels = new ArrayList<>();
        List<List<Morsel>> byChain = new ArrayList<>();
        for (int c = 0; c < chains.size(); c++) {
            Chain ch = chains.get(c);
            if (!buildTables(ch)) {
                splits.add(null);
                continue;
            }
            Splits s = splits(ch);
            splits.add(s);
            List<Morsel> own = new ArrayList<>();
            for (int i = 0; i < s.count(); i++) {
                own.add(new Morsel(c, i, morsels.size() + own.size()));
            }
            if (!own.isEmpty()) {
                active.add(c);
                morsels.addAll(own);
                byChain.add(own);
            }
        }
        morselCount = morsels.size();
        int n = Math.min(threads, morsels.size());
        if (n <= 1) {
            local = new ArrayList<>();
            for (List<Morsel> own : byChain) {
                local.add(own.iterator());
            }
            return;
        }
        Exchange ex = new Exchange(morsels, outputs != null ? Exchange.Mode.COLLECT
                : ordered ? Exchange.Mode.ORDERED : Exchange.Mode.UNORDERED, n, context());
        List<List<Operator>> copies = new ArrayList<>();
        List<List<MorselScan>> scans = new ArrayList<>();
        List<Output> outs = new ArrayList<>();
        for (int w = 0; w < n; w++) {
            List<Operator> ops = new ArrayList<>();
            List<MorselScan> own = new ArrayList<>();
            for (int c : active) {
                MorselScan scan = new MorselScan(chains.get(c).scanLabel(), chains.get(c).scan(), c, splits.get(c),
                        ex);
                scan.attach(context());
                Operator op = chains.get(c).build().apply(scan, tables);
                operators.add(op);
                ops.add(op);
                own.add(scan);
            }
            copies.add(ops);
            scans.add(own);
            outs.add(outputs == null ? ex::put : outputs.apply(ex, n));
        }
        exchange = ex;
        context().register(new BatchReader() {
            @Override
            public Batch next() {
                return null;
            }

            @Override
            public void cancel() {
                ex.wake();
            }
        });
        for (int w = 0; w < n; w++) {
            List<Operator> ops = copies.get(w);
            List<MorselScan> own = scans.get(w);
            Output out = outs.get(w);
            try {
                workers.add(Workers.submit(() -> run(ex, ops, own, out)));
            } catch (RuntimeException | Error e) {
                // The workers not started never finish; the copies they would close stay in the list of operators.
                for (int rest = w; rest < n; rest++) {
                    ex.finished();
                }
                throw e;
            }
            operators.removeAll(ops);
        }
    }

    /**
     * Reads the build sides of the joins of a chain into their tables, from
     * the top down, and tells whether the chain reads its scan: not below
     * an empty build side of an {@code INNER} or {@code SEMI} join.
     */
    private boolean buildTables(Chain ch) {
        for (Build b : ch.builds()) {
            JoinTable table;
            try {
                table = Timing.run(context(), b.label(), () -> JoinBuild.read(b.right(), b.join(), b.label(),
                        budget, codegen, reserved, context()));
            } catch (QueryException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new QueryException(b.label(), e.getMessage() == null ? e.getClass().getSimpleName()
                        : e.getMessage(), e);
            }
            tables.put(b.join(), table);
            JoinType type = b.join().type();
            if (table.rowCount() == 0 && (type == JoinType.INNER || type == JoinType.SEMI)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the splits of the scan of a chain, a failure under the label of
     * the scan, which counts the time and what the source reads for it.
     */
    private Splits splits(Chain c) {
        try {
            return Timing.run(context(), c.scanLabel(), () -> {
                try (SourceCounters.Scope scope = SourceCounters.enter(context().source(c.scanLabel()))) {
                    return source.splits(c.scan(), threads, context());
                }
            });
        } catch (QueryException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new QueryException(c.scanLabel(), e.getMessage() == null ? e.getClass().getSimpleName()
                    : e.getMessage(), e);
        }
    }

    /** Builds a copy of a chain over a scan of its morsels. */
    private Operator open(int c, MorselScan.Morsels morsels) {
        Chain ch = chains.get(c);
        MorselScan scan = new MorselScan(ch.scanLabel(), ch.scan(), c, splits.get(c), morsels);
        scan.attach(context());
        localScans.add(scan);
        return ch.build().apply(scan, tables);
    }

    /** The work of a worker: its copies of the chains, one after the other, then it closes them. */
    private static Void run(Exchange exchange, List<Operator> ops, List<MorselScan> scans, Output out) {
        MorselScan scan = null;
        try {
            for (int c = 0; c < ops.size(); c++) {
                Operator op = ops.get(c);
                scan = scans.get(c);
                for (Batch b = op.next(); b != null; b = op.next()) {
                    if (!out.put(scan.morsel(), b)) {
                        return null;
                    }
                }
            }
        } catch (RuntimeException | Error e) {
            exchange.fail(scan == null ? null : scan.morsel(), e);
        } finally {
            for (Operator op : ops) {
                try {
                    op.close();
                } catch (RuntimeException e) {
                    exchange.fail(null, e);
                }
            }
            exchange.finished();
        }
        return null;
    }

    @Override
    public void close() {
        if (exchange != null) {
            exchange.close();
            boolean interrupted = false;
            for (Future<?> f : workers) {
                while (true) {
                    try {
                        f.get();
                        break;
                    } catch (InterruptedException e) {
                        interrupted = true;
                    } catch (ExecutionException e) {
                        break;
                    }
                }
            }
            workers.clear();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        for (Operator op : operators) {
            op.close();
        }
        operators.clear();
        for (Chain ch : chains) {
            for (Build b : ch.builds()) {
                b.right().close();
            }
        }
        budget.release(reserved.getAndSet(0));
    }
}
