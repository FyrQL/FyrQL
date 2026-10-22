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

package net.fyrql.flight;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import net.fyrql.FyrQL;
import net.fyrql.PreparedQuery;
import net.fyrql.QueryResult;
import net.fyrql.Session;
import net.fyrql.SqlStatement;
import net.fyrql.arrow.ArrowToBatch;
import net.fyrql.arrow.BatchToArrow;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.exec.QueryContext;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.bind.Settings;
import net.fyrql.sql.parse.Parameters;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.CancelFlightInfoRequest;
import org.apache.arrow.flight.CancelStatus;
import org.apache.arrow.flight.CloseSessionRequest;
import org.apache.arrow.flight.CloseSessionResult;
import org.apache.arrow.flight.Criteria;
import org.apache.arrow.flight.FlightDescriptor;
import org.apache.arrow.flight.FlightEndpoint;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.GetSessionOptionsRequest;
import org.apache.arrow.flight.GetSessionOptionsResult;
import org.apache.arrow.flight.PutResult;
import org.apache.arrow.flight.Result;
import org.apache.arrow.flight.SchemaResult;
import org.apache.arrow.flight.SessionOptionValue;
import org.apache.arrow.flight.SessionOptionValueFactory;
import org.apache.arrow.flight.SessionOptionValueVisitor;
import org.apache.arrow.flight.SetSessionOptionsRequest;
import org.apache.arrow.flight.SetSessionOptionsResult;
import org.apache.arrow.flight.Ticket;
import org.apache.arrow.flight.sql.FlightSqlProducer;
import org.apache.arrow.flight.sql.SqlInfoBuilder;
import org.apache.arrow.flight.sql.impl.FlightSql;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Answers the calls of Flight SQL with an engine of FyrQL. A query is
 * bound when a client asks for its flight info, which holds the schema of
 * the result and one ticket for this server; the ticket runs the query
 * once, and the batches go out as they come from the engine, as fast as
 * the client takes them. A prepared statement takes one row of values,
 * which keep their Arrow types as the setters of the JDBC driver keep
 * theirs.
 * <p>
 * A statement that writes runs to its end when a client sends it as an
 * update, and the answer is the number of rows written; a prepared one runs
 * once for each row of values sent with the update. A client that asks for
 * the flight info of such a statement as for a query runs it then, and
 * the ticket gives the row of the column {@code Count}. A prepared
 * statement that writes has a result without columns, as clients such as
 * the JDBC driver of Arrow take it for an update. Each statement that
 * writes counts as a query while it runs; the server has no transactions.
 * <p>
 * Each client is a session of the engine, see {@link Sessions}: its
 * statements bind and run with the settings of the session, which
 * {@code SET} and the actions {@code SetSessionOptions},
 * {@code GetSessionOptions} and {@code CloseSession} of Flight change and
 * read. A statement keeps the session it was bound in; a prepared statement
 * binds in the session of the call that runs it, along the search path it
 * was prepared with.
 */
final class FyrQLFlightSqlProducer implements FlightSqlProducer {

    private static final String NOT_AN_UPDATE = "A query returns rows, not a number of rows written; run it"
            + " as a query";
    private static final long WAIT_MILLIS = 100;
    private static final Duration MIN_EXPIRY_PERIOD = Duration.ofSeconds(1);
    private static final Duration MAX_EXPIRY_PERIOD = Duration.ofMinutes(1);

    /** The batches of a query or of {@code EXPLAIN}, or the row of the count of a statement that wrote. */
    private interface Rows extends AutoCloseable {
        /** Returns the next batch, or {@code null} after the last one. */
        Batch next();

        @Override
        void close();
    }

    /**
     * A query or {@code EXPLAIN} the server gave out a ticket for, which runs
     * when its stream is read, or the count of a statement that wrote; with
     * the contexts of its runs.
     */
    private static final class Statement {
        final org.apache.arrow.vector.types.pojo.Schema schema;
        final SqlStatement statement;
        final Batch lines;
        final Session session;
        final Set<QueryContext> runs = ConcurrentHashMap.newKeySet();
        volatile boolean cancelled;

        Statement(Schema schema, SqlStatement statement, Batch lines, Session session) {
            this.schema = Metadata.resultSchema(schema);
            this.statement = statement;
            this.lines = lines;
            this.session = session;
        }

        Rows open(QueryContext context) {
            if (statement == null) {
                return new Rows() {
                    private boolean sent;

                    @Override
                    public Batch next() {
                        if (sent) {
                            return null;
                        }
                        sent = true;
                        return lines;
                    }

                    @Override
                    public void close() {
                    }
                };
            }
            QueryResult result = session.execute(statement, context);
            return new Rows() {
                @Override
                public Batch next() {
                    return result.nextBatch();
                }

                @Override
                public void close() {
                    result.close();
                }
            };
        }

        void cancel() {
            cancelled = true;
            for (QueryContext c : runs) {
                c.cancel();
            }
        }
    }

    /** The values of the parameters of a prepared statement, with their types. */
    private record Values(List<Object> values, List<DataType> types) {
    }

    /** A prepared statement and the values bound last. */
    private static final class Prepared {
        final PreparedQuery query;
        final Parameters parameters;
        final org.apache.arrow.vector.types.pojo.Schema schema;
        final org.apache.arrow.vector.types.pojo.Schema parameterSchema;
        volatile Values values;

        Prepared(PreparedQuery query) {
            this.query = query;
            this.parameters = Parameters.parse(query.sql());
            this.schema = Metadata.resultSchema(query.writes() ? new Schema(List.of()) : query.schema());
            List<Schema.Field> fields = new ArrayList<>();
            List<DataType> types = parameterTypes();
            for (int i = 0; i < types.size(); i++) {
                fields.add(new Schema.Field("$" + (i + 1), types.get(i), true));
            }
            this.parameterSchema = Metadata.resultSchema(new Schema(fields));
        }

        List<DataType> parameterTypes() {
            return query.parameterTypes();
        }

        /**
         * Binds the statement in a session with a row of values: with values
         * of the types of the parameters, or {@code NULL}, as the engine
         * prepared it; otherwise with literals of the types of the values.
         *
         * @param v the values, {@code null} if none were bound
         */
        SqlStatement bind(Values v, Session session) {
            List<DataType> types = parameterTypes();
            if (v == null) {
                if (!types.isEmpty()) {
                    throw FlightErrors.invalid("The prepared statement has " + types.size() + " parameters and no"
                            + " values; bind a row of values first");
                }
                return session.statement(query, List.of());
            }
            boolean fit = true;
            for (int i = 0; i < types.size() && fit; i++) {
                fit = v.values().get(i) == null || v.types().get(i).equals(types.get(i));
            }
            if (fit) {
                return session.statement(query, v.values());
            }
            String[] literals = new String[types.size()];
            for (int i = 0; i < literals.length; i++) {
                Object value = v.values().get(i);
                literals[i] = Parameters.literal(value, value == null ? types.get(i) : v.types().get(i));
            }
            return session.statement(parameters.render(literals), query.searchPath());
        }
    }

    /**
     * Waits until the client takes the next batch, without spinning: the
     * stream signals when it is ready or canceled, and a query canceled by
     * the server is seen after at most {@value #WAIT_MILLIS} ms.
     */
    private static final class Backpressure {
        private final ServerStreamListener listener;
        private final QueryContext context;
        private final Object lock = new Object();

        Backpressure(ServerStreamListener listener, QueryContext context) {
            this.listener = listener;
            this.context = context;
            listener.setOnCancelHandler(() -> {
                context.cancel();
                signal();
            });
            try {
                listener.setOnReadyHandler(this::signal);
            } catch (UnsupportedOperationException e) {
                // The waits end after their timeout instead.
            }
        }

        private void signal() {
            synchronized (lock) {
                lock.notifyAll();
            }
        }

        /** Returns {@code false} if the client canceled the stream; the query may be canceled meanwhile. */
        boolean await() {
            synchronized (lock) {
                while (!listener.isReady() && !listener.isCancelled() && !context.isCancelled()) {
                    try {
                        lock.wait(WAIT_MILLIS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
            return !listener.isCancelled();
        }
    }

    private final FyrQL engine;
    private final BufferAllocator allocator;
    private final int maxQueries;
    private final Semaphore queries;
    /** Held for reading while a call holds buffers of the allocator, for writing once the server stopped. */
    private final ReadWriteLock buffers = new ReentrantReadWriteLock();
    private final Handles<Statement> statements;
    private final Handles<Prepared> prepared;
    private final ScheduledExecutorService expiry;
    private final SqlInfoBuilder sqlInfo;
    private final Sessions sessions;

    /**
     * @param engine the engine that runs the queries; it stays open when the producer closes
     * @param allocator the allocator of the Arrow buffers of the results
     * @param config the handle timeout and the most queries at once
     * @param sessions the sessions of the clients, which the producer expires with the handles
     */
    FyrQLFlightSqlProducer(FyrQL engine, BufferAllocator allocator, ServerConfig config, Sessions sessions) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        this.maxQueries = config.maxQueries() > 0 ? config.maxQueries() : 2 * engine.threads();
        this.queries = new Semaphore(maxQueries);
        long timeout = config.handleTimeout().toNanos();
        this.statements = new Handles<>("statement", timeout, System::nanoTime, s -> !s.runs.isEmpty());
        this.prepared = new Handles<>("prepared statement", timeout, System::nanoTime, p -> false);
        this.expiry = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "fyrql-flight-handles");
            t.setDaemon(true);
            return t;
        });
        long period = Math.min(Math.max(timeout / 2, MIN_EXPIRY_PERIOD.toNanos()), MAX_EXPIRY_PERIOD.toNanos());
        expiry.scheduleWithFixedDelay(() -> {
            statements.expire();
            prepared.expire();
            sessions.expire();
        }, period, period, TimeUnit.NANOSECONDS);
        this.sqlInfo = Metadata.sqlInfo(FlightSqlServer.VERSION, FlightSqlServer.ARROW_VERSION);
    }

    /** Returns the most queries that run at once. */
    int maxQueries() {
        return maxQueries;
    }

    /** Returns the number of queries that run. */
    int runningQueries() {
        return maxQueries - queries.availablePermits();
    }

    /**
     * Waits until no call holds buffers of the allocator, at most a time in
     * milliseconds: a call may still free its buffers when its client has
     * read the last of them and the server has stopped.
     *
     * @return whether no call holds buffers any more
     */
    boolean awaitBuffers(long millis) throws InterruptedException {
        Lock all = buffers.writeLock();
        if (!all.tryLock(millis, TimeUnit.MILLISECONDS)) {
            return false;
        }
        all.unlock();
        return true;
    }

    /** Returns the number of statements and prepared statements the server holds handles for. */
    int handles() {
        return statements.size() + prepared.size();
    }

    /** Returns the number of sessions of clients the server holds. */
    int sessions() {
        return sessions.size();
    }

    private static String owner(CallContext context) {
        return Objects.requireNonNullElse(context.peerIdentity(), "");
    }

    /** Returns the session of the client of a call. */
    private Session session(CallContext context) {
        return sessions.session(context, owner(context));
    }

    private static Ticket ticket(Message command) {
        return new Ticket(Any.pack(command).toByteArray());
    }

    private static FlightInfo info(org.apache.arrow.vector.types.pojo.Schema schema, FlightDescriptor descriptor,
            Ticket ticket) {
        return new FlightInfo(schema, descriptor, List.of(new FlightEndpoint(ticket)), -1, -1);
    }

    private static void noTransaction(boolean transaction) {
        if (transaction) {
            throw FlightErrors.unimplemented("FyrQL has no transactions");
        }
    }

    /** Binds a statement in a session; a statement that writes runs now, see {@link #run(SqlStatement, Session)}. */
    private Statement statement(String sql, Session session) {
        return run(session.statement(sql), session);
    }

    /** Binds a prepared statement with the values bound last; a statement that writes runs now. */
    private Statement statement(Prepared p, Session session) {
        return run(p.bind(p.values, session), session);
    }

    /**
     * Returns the statement of a query or of {@code EXPLAIN}, which runs when
     * its stream is read, also {@code EXPLAIN ANALYZE} of a statement that
     * writes; a statement that writes runs to its end now, and its result is
     * the row of its count.
     */
    private Statement run(SqlStatement bound, Session session) {
        if (bound.isQuery()) {
            return new Statement(bound.schema(), bound, null, session);
        }
        Object[] count = {update(bound, session)};
        return new Statement(bound.schema(), null, Batch.ofRows(bound.schema(), List.<Object[]>of(count)), session);
    }

    /**
     * Runs a statement that writes in a session to its end, unless more
     * queries than allowed run already, and returns the rows it wrote;
     * {@code SET} changes the session.
     */
    private long update(SqlStatement statement, Session session) {
        if (!queries.tryAcquire()) {
            throw busy();
        }
        try {
            return session.update(statement, new QueryContext());
        } finally {
            queries.release();
        }
    }

    private FlightRuntimeException busy() {
        return CallStatus.RESOURCE_EXHAUSTED.withDescription("The server runs " + maxQueries
                + " queries, the most it runs at once (maxQueries); try again later").toRuntimeException();
    }

    /** Answers a put with one message and completes it. */
    private void complete(StreamListener<PutResult> ackStream, Message message) {
        byte[] result = message.toByteArray();
        buffers.readLock().lock();
        try (ArrowBuf buffer = allocator.buffer(result.length)) {
            buffer.writeBytes(result);
            ackStream.onNext(PutResult.metadata(buffer));
        } finally {
            buffers.readLock().unlock();
        }
        ackStream.onCompleted();
    }

    /**
     * Returns the types of a row of values for the parameters: the types of
     * the columns, but a column of the Arrow type {@code Null}, such as
     * pyarrow makes of {@code None}, has the type of its parameter.
     */
    private static List<DataType> types(VectorSchemaRoot root, Batch batch, List<DataType> parameterTypes) {
        List<Field> fields = root.getSchema().getFields();
        if (fields.size() != parameterTypes.size()) {
            throw FlightErrors.invalid("The query has " + parameterTypes.size() + " parameters, not "
                    + fields.size() + " values");
        }
        List<DataType> types = new ArrayList<>(fields.size());
        for (int i = 0; i < fields.size(); i++) {
            boolean untyped = fields.get(i).getType() instanceof ArrowType.Null;
            types.add(untyped ? parameterTypes.get(i) : batch.schema().type(i));
        }
        return types;
    }

    /**
     * Runs a statement and sends its batches, unless more queries than
     * allowed run already. The query leaves its place before the server
     * sends the end of its stream or its error, so that a client that has
     * read it to its end can run its next query at once.
     */
    private void run(Statement s, ServerStreamListener listener) {
        if (s.cancelled) {
            listener.error(CallStatus.CANCELLED.withDescription("The query was canceled").toRuntimeException());
            return;
        }
        if (!queries.tryAcquire()) {
            listener.error(busy());
            return;
        }
        QueryContext context = new QueryContext();
        s.runs.add(context);
        boolean released = false;
        buffers.readLock().lock();
        try {
            Backpressure ready = new Backpressure(listener, context);
            if (s.cancelled) {
                context.cancel();
            }
            try (BufferAllocator a = allocator.newChildAllocator("query", 0, Long.MAX_VALUE);
                    Rows rows = s.open(context);
                    VectorSchemaRoot root = VectorSchemaRoot.create(s.schema, a)) {
                listener.start(root);
                for (Batch b = rows.next(); b != null; b = rows.next()) {
                    BatchToArrow.fill(b, root);
                    if (!ready.await()) {
                        return;
                    }
                    if (context.isCancelled()) {
                        queries.release();
                        released = true;
                        CallStatus status = context.reason() == QueryContext.Reason.TIMEOUT ? CallStatus.TIMED_OUT
                                : CallStatus.CANCELLED;
                        listener.error(status.withDescription(context.reason().message()).toRuntimeException());
                        return;
                    }
                    listener.putNext();
                }
                queries.release();
                released = true;
                listener.completed();
            }
        } catch (RuntimeException e) {
            if (!released) {
                queries.release();
                released = true;
            }
            if (!listener.isCancelled()) {
                listener.error(FlightErrors.of(e));
            }
        } finally {
            buffers.readLock().unlock();
            s.runs.remove(context);
            if (!released) {
                queries.release();
            }
        }
    }

    // ---------------------------------------------------------------- statements

    @Override
    public FlightInfo getFlightInfoStatement(FlightSql.CommandStatementQuery command, CallContext context,
            FlightDescriptor descriptor) {
        try {
            noTransaction(command.hasTransactionId());
            Statement s = statement(command.getQuery(), session(context));
            ByteString handle = statements.add(owner(context), s);
            return info(s.schema, descriptor, ticket(FlightSql.TicketStatementQuery.newBuilder()
                    .setStatementHandle(handle).build()));
        } catch (RuntimeException e) {
            throw FlightErrors.of(e);
        }
    }

    /** Returns the schema of the result, {@code Count} for a statement that writes, which does not run. */
    @Override
    public SchemaResult getSchemaStatement(FlightSql.CommandStatementQuery command, CallContext context,
            FlightDescriptor descriptor) {
        try {
            noTransaction(command.hasTransactionId());
            return new SchemaResult(Metadata.resultSchema(session(context).statement(command.getQuery())
                    .schema()));
        } catch (RuntimeException e) {
            throw FlightErrors.of(e);
        }
    }

    @Override
    public void getStreamStatement(FlightSql.TicketStatementQuery ticket, CallContext context,
            ServerStreamListener listener) {
        ByteString handle = ticket.getStatementHandle();
        Statement s;
        try {
            s = statements.get(handle, owner(context));
        } catch (FlightRuntimeException e) {
            listener.error(e);
            return;
        }
        try {
            run(s, listener);
        } finally {
            statements.remove(handle, owner(context));
        }
    }

    @Override
    public void cancelFlightInfo(CancelFlightInfoRequest request, CallContext context,
            StreamListener<CancelStatus> listener) {
        CancelStatus status = CancelStatus.NOT_CANCELLABLE;
        try {
            for (FlightEndpoint endpoint : request.getInfo().getEndpoints()) {
                Any any = Any.parseFrom(endpoint.getTicket().getBytes());
                if (any.is(FlightSql.TicketStatementQuery.class)) {
                    Statement s = statements.find(any.unpack(FlightSql.TicketStatementQuery.class)
                            .getStatementHandle(), owner(context));
                    if (s != null) {
                        s.cancel();
                        status = CancelStatus.CANCELLED;
                    }
                }
            }
        } catch (InvalidProtocolBufferException e) {
            listener.onError(FlightErrors.invalid("The ticket is none of FyrQL"));
            return;
        }
        listener.onNext(status);
        listener.onCompleted();
    }

    /** Runs a statement that writes and answers with the rows it wrote; a query fails. */
    @Override
    public Runnable acceptPutStatement(FlightSql.CommandStatementUpdate command, CallContext context,
            FlightStream flightStream, StreamListener<PutResult> ackStream) {
        Session session = session(context);
        return () -> {
            try {
                noTransaction(command.hasTransactionId());
                SqlStatement statement = session.statement(command.getQuery());
                if (statement.isQuery()) {
                    throw FlightErrors.invalid(NOT_AN_UPDATE);
                }
                long count = update(statement, session);
                complete(ackStream, FlightSql.DoPutUpdateResult.newBuilder().setRecordCount(count).build());
            } catch (RuntimeException e) {
                ackStream.onError(FlightErrors.of(e));
            }
        };
    }

    // ---------------------------------------------------------------- prepared statements

    @Override
    public void createPreparedStatement(FlightSql.ActionCreatePreparedStatementRequest request, CallContext context,
            StreamListener<Result> listener) {
        try {
            noTransaction(request.hasTransactionId());
            Prepared p = new Prepared(session(context).prepare(request.getQuery()));
            ByteString handle = prepared.add(owner(context), p);
            FlightSql.ActionCreatePreparedStatementResult result = FlightSql.ActionCreatePreparedStatementResult
                    .newBuilder().setPreparedStatementHandle(handle)
                    .setDatasetSchema(ByteString.copyFrom(Metadata.serialize(p.schema)))
                    .setParameterSchema(ByteString.copyFrom(Metadata.serialize(p.parameterSchema))).build();
            listener.onNext(new Result(Any.pack(result).toByteArray()));
            listener.onCompleted();
        } catch (RuntimeException e) {
            listener.onError(FlightErrors.of(e));
        }
    }

    @Override
    public void closePreparedStatement(FlightSql.ActionClosePreparedStatementRequest request, CallContext context,
            StreamListener<Result> listener) {
        prepared.remove(request.getPreparedStatementHandle(), owner(context));
        listener.onCompleted();
    }

    /**
     * Binds a row of values to a prepared statement. A column of the Arrow
     * type {@code Null}, such as pyarrow makes of {@code None}, is a
     * {@code NULL} of the type of its parameter.
     */
    @Override
    public Runnable acceptPutPreparedStatementQuery(FlightSql.CommandPreparedStatementQuery command,
            CallContext context, FlightStream flightStream, StreamListener<PutResult> ackStream) {
        return () -> {
            try {
                ByteString handle = command.getPreparedStatementHandle();
                Prepared p = prepared.get(handle, owner(context));
                List<DataType> parameterTypes = p.parameterTypes();
                Values bound = null;
                int rows = 0;
                while (flightStream.next()) {
                    VectorSchemaRoot root = flightStream.getRoot();
                    if (root.getRowCount() == 0) {
                        continue;
                    }
                    rows += root.getRowCount();
                    if (rows > 1) {
                        throw FlightErrors.invalid("A prepared statement of FyrQL takes one row of values, not "
                                + rows + " or more; send the rows of a statement that writes as an update");
                    }
                    Batch batch = ArrowToBatch.batch(root, flightStream.getDictionaryProvider());
                    bound = new Values(Arrays.asList(batch.row(0)), types(root, batch, parameterTypes));
                }
                if (bound == null && !parameterTypes.isEmpty()) {
                    throw FlightErrors.invalid("The query has " + parameterTypes.size() + " parameters, not 0"
                            + " values");
                }
                p.values = bound;
                complete(ackStream, FlightSql.DoPutPreparedStatementResult.newBuilder()
                        .setPreparedStatementHandle(handle).build());
            } catch (RuntimeException e) {
                ackStream.onError(FlightErrors.of(e));
            }
        };
    }

    @Override
    public FlightInfo getFlightInfoPreparedStatement(FlightSql.CommandPreparedStatementQuery command,
            CallContext context, FlightDescriptor descriptor) {
        try {
            Statement s = statement(prepared.get(command.getPreparedStatementHandle(), owner(context)),
                    session(context));
            ByteString handle = statements.add(owner(context), s);
            return info(s.schema, descriptor, ticket(FlightSql.TicketStatementQuery.newBuilder()
                    .setStatementHandle(handle).build()));
        } catch (RuntimeException e) {
            throw FlightErrors.of(e);
        }
    }

    @Override
    public SchemaResult getSchemaPreparedStatement(FlightSql.CommandPreparedStatementQuery command,
            CallContext context, FlightDescriptor descriptor) {
        return new SchemaResult(prepared.get(command.getPreparedStatementHandle(), owner(context)).schema);
    }

    @Override
    public void getStreamPreparedStatement(FlightSql.CommandPreparedStatementQuery command, CallContext context,
            ServerStreamListener listener) {
        Statement s;
        try {
            s = statement(prepared.get(command.getPreparedStatementHandle(), owner(context)), session(context));
        } catch (RuntimeException e) {
            listener.error(FlightErrors.of(e));
            return;
        }
        run(s, listener);
    }

    /**
     * Runs a prepared statement that writes once for each row of values
     * the client sends, or once if it has no parameters, and answers with
     * the rows written by all runs; a column of the Arrow type {@code Null}
     * is a {@code NULL} of the type of its parameter. Each run writes on its
     * own: the runs before one that fails keep what they wrote. A query
     * fails.
     */
    @Override
    public Runnable acceptPutPreparedStatementUpdate(FlightSql.CommandPreparedStatementUpdate command,
            CallContext context, FlightStream flightStream, StreamListener<PutResult> ackStream) {
        Session session = session(context);
        return () -> {
            try {
                Prepared p = prepared.get(command.getPreparedStatementHandle(), owner(context));
                if (!p.query.writes()) {
                    throw FlightErrors.invalid(NOT_AN_UPDATE);
                }
                List<DataType> parameterTypes = p.parameterTypes();
                long count = 0;
                long rows = 0;
                while (flightStream.next()) {
                    VectorSchemaRoot root = flightStream.getRoot();
                    if (root.getRowCount() == 0 || parameterTypes.isEmpty()) {
                        continue;
                    }
                    Batch batch = ArrowToBatch.batch(root, flightStream.getDictionaryProvider());
                    List<DataType> types = types(root, batch, parameterTypes);
                    for (int r = 0; r < batch.rowCount(); r++) {
                        rows++;
                        try {
                            count += update(p.bind(new Values(Arrays.asList(batch.row(r)), types), session),
                                    session);
                        } catch (RuntimeException e) {
                            FlightRuntimeException f = FlightErrors.of(e);
                            throw f.status().withDescription("Row " + rows + " of the values fails, after "
                                    + count + " rows written: " + f.status().description()).withCause(e)
                                    .toRuntimeException();
                        }
                    }
                }
                if (parameterTypes.isEmpty()) {
                    count = update(p.bind(null, session), session);
                }
                complete(ackStream, FlightSql.DoPutUpdateResult.newBuilder().setRecordCount(count).build());
            } catch (RuntimeException e) {
                ackStream.onError(FlightErrors.of(e));
            }
        };
    }

    // ---------------------------------------------------------------- metadata

    private void send(org.apache.arrow.vector.types.pojo.Schema schema, ServerStreamListener listener,
            Supplier<List<Object[]>> rows) {
        buffers.readLock().lock();
        try {
            Metadata.send(schema, rows.get(), allocator, listener);
        } catch (RuntimeException e) {
            listener.error(FlightErrors.of(e));
        } finally {
            buffers.readLock().unlock();
        }
    }

    @Override
    public FlightInfo getFlightInfoSqlInfo(FlightSql.CommandGetSqlInfo command, CallContext context,
            FlightDescriptor descriptor) {
        return info(Schemas.GET_SQL_INFO_SCHEMA, descriptor, ticket(command));
    }

    @Override
    public void getStreamSqlInfo(FlightSql.CommandGetSqlInfo command, CallContext context,
            ServerStreamListener listener) {
        try {
            sqlInfo.send(command.getInfoList(), listener);
        } catch (RuntimeException e) {
            listener.error(FlightErrors.of(e));
        }
    }

    @Override
    public FlightInfo getFlightInfoTypeInfo(FlightSql.CommandGetXdbcTypeInfo command, CallContext context,
            FlightDescriptor descriptor) {
        return info(Schemas.GET_TYPE_INFO_SCHEMA, descriptor, ticket(command));
    }

    @Override
    public void getStreamTypeInfo(FlightSql.CommandGetXdbcTypeInfo command, CallContext context,
            ServerStreamListener listener) {
        send(Schemas.GET_TYPE_INFO_SCHEMA, listener, () -> Metadata.types(command));
    }

    @Override
    public FlightInfo getFlightInfoCatalogs(FlightSql.CommandGetCatalogs command, CallContext context,
            FlightDescriptor descriptor) {
        return info(Schemas.GET_CATALOGS_SCHEMA, descriptor, ticket(command));
    }

    @Override
    public void getStreamCatalogs(CallContext context, ServerStreamListener listener) {
        send(Schemas.GET_CATALOGS_SCHEMA, listener, () -> Metadata.catalogs(engine));
    }

    @Override
    public FlightInfo getFlightInfoSchemas(FlightSql.CommandGetDbSchemas command, CallContext context,
            FlightDescriptor descriptor) {
        return info(Schemas.GET_SCHEMAS_SCHEMA, descriptor, ticket(command));
    }

    @Override
    public void getStreamSchemas(FlightSql.CommandGetDbSchemas command, CallContext context,
            ServerStreamListener listener) {
        send(Schemas.GET_SCHEMAS_SCHEMA, listener, () -> Metadata.schemas(engine, command));
    }

    private static org.apache.arrow.vector.types.pojo.Schema tablesSchema(FlightSql.CommandGetTables command) {
        return command.getIncludeSchema() ? Schemas.GET_TABLES_SCHEMA : Schemas.GET_TABLES_SCHEMA_NO_SCHEMA;
    }

    @Override
    public FlightInfo getFlightInfoTables(FlightSql.CommandGetTables command, CallContext context,
            FlightDescriptor descriptor) {
        return info(tablesSchema(command), descriptor, ticket(command));
    }

    @Override
    public void getStreamTables(FlightSql.CommandGetTables command, CallContext context,
            ServerStreamListener listener) {
        send(tablesSchema(command), listener, () -> Metadata.tables(engine, command));
    }

    @Override
    public FlightInfo getFlightInfoTableTypes(FlightSql.CommandGetTableTypes command, CallContext context,
            FlightDescriptor descriptor) {
        return info(Schemas.GET_TABLE_TYPES_SCHEMA, descriptor, ticket(command));
    }

    @Override
    public void getStreamTableTypes(CallContext context, ServerStreamListener listener) {
        send(Schemas.GET_TABLE_TYPES_SCHEMA, listener, () -> List.<Object[]>of(new Object[] {Metadata.TABLE}));
    }

    @Override
    public FlightInfo getFlightInfoPrimaryKeys(FlightSql.CommandGetPrimaryKeys command, CallContext context,
            FlightDescriptor descriptor) {
        return info(Schemas.GET_PRIMARY_KEYS_SCHEMA, descriptor, ticket(command));
    }

    @Override
    public void getStreamPrimaryKeys(FlightSql.CommandGetPrimaryKeys command, CallContext context,
            ServerStreamListener listener) {
        send(Schemas.GET_PRIMARY_KEYS_SCHEMA, listener, List::of);
    }

    @Override
    public FlightInfo getFlightInfoExportedKeys(FlightSql.CommandGetExportedKeys command, CallContext context,
            FlightDescriptor descriptor) {
        return info(Schemas.GET_EXPORTED_KEYS_SCHEMA, descriptor, ticket(command));
    }

    @Override
    public FlightInfo getFlightInfoImportedKeys(FlightSql.CommandGetImportedKeys command, CallContext context,
            FlightDescriptor descriptor) {
        return info(Schemas.GET_IMPORTED_KEYS_SCHEMA, descriptor, ticket(command));
    }

    @Override
    public FlightInfo getFlightInfoCrossReference(FlightSql.CommandGetCrossReference command, CallContext context,
            FlightDescriptor descriptor) {
        return info(Schemas.GET_CROSS_REFERENCE_SCHEMA, descriptor, ticket(command));
    }

    @Override
    public void getStreamExportedKeys(FlightSql.CommandGetExportedKeys command, CallContext context,
            ServerStreamListener listener) {
        send(Schemas.GET_EXPORTED_KEYS_SCHEMA, listener, List::of);
    }

    @Override
    public void getStreamImportedKeys(FlightSql.CommandGetImportedKeys command, CallContext context,
            ServerStreamListener listener) {
        send(Schemas.GET_IMPORTED_KEYS_SCHEMA, listener, List::of);
    }

    @Override
    public void getStreamCrossReference(FlightSql.CommandGetCrossReference command, CallContext context,
            ServerStreamListener listener) {
        send(Schemas.GET_CROSS_REFERENCE_SCHEMA, listener, List::of);
    }

    // ---------------------------------------------------------------- sessions

    /** The text of a value of a session option, {@code null} for an empty value or a list. */
    private static final SessionOptionValueVisitor<String> TEXT = new SessionOptionValueVisitor<>() {
        @Override
        public String visit(String value) {
            return value;
        }

        @Override
        public String visit(boolean value) {
            return Boolean.toString(value);
        }

        @Override
        public String visit(long value) {
            return Long.toString(value);
        }

        @Override
        public String visit(double value) {
            return Double.toString(value);
        }

        @Override
        public String visit(String[] value) {
            return null;
        }

        @Override
        public String visit(Void value) {
            return null;
        }
    };

    /**
     * Sets settings of the session of the client, as {@code SET} does; an
     * empty value resets one, as {@code RESET} does. A name that is no
     * setting is {@code INVALID_NAME}, a value that does not fit it, or a
     * list, {@code INVALID_VALUE}; the other options are set all the same.
     */
    @Override
    public void setSessionOptions(SetSessionOptionsRequest request, CallContext context,
            StreamListener<SetSessionOptionsResult> listener) {
        try {
            Session session = session(context);
            Map<String, SetSessionOptionsResult.Error> errors = new LinkedHashMap<>();
            for (Map.Entry<String, SessionOptionValue> option : request.getSessionOptions().entrySet()) {
                String name = option.getKey();
                if (Settings.name(name) == null) {
                    errors.put(name, new SetSessionOptionsResult.Error(
                            SetSessionOptionsResult.ErrorValue.INVALID_NAME));
                    continue;
                }
                SessionOptionValue value = option.getValue();
                String text = value.acceptVisitor(TEXT);
                try {
                    if (value.isEmpty()) {
                        session.reset(name);
                    } else if (text == null) {
                        throw new IllegalArgumentException("A list is no value of " + name);
                    } else {
                        session.set(name, text);
                    }
                } catch (SqlException | IllegalArgumentException e) {
                    errors.put(name, new SetSessionOptionsResult.Error(
                            SetSessionOptionsResult.ErrorValue.INVALID_VALUE));
                }
            }
            listener.onNext(new SetSessionOptionsResult(errors));
            listener.onCompleted();
        } catch (RuntimeException e) {
            listener.onError(FlightErrors.of(e));
        }
    }

    /** Returns the settings of the session of the client, as {@code SHOW ALL} shows them, as strings. */
    @Override
    public void getSessionOptions(GetSessionOptionsRequest request, CallContext context,
            StreamListener<GetSessionOptionsResult> listener) {
        try {
            Map<String, SessionOptionValue> options = new LinkedHashMap<>();
            for (Map.Entry<String, String> s : session(context).settings().entrySet()) {
                options.put(s.getKey(), SessionOptionValueFactory.makeSessionOptionValue(s.getValue()));
            }
            listener.onNext(new GetSessionOptionsResult(options));
            listener.onCompleted();
        } catch (RuntimeException e) {
            listener.onError(FlightErrors.of(e));
        }
    }

    /**
     * Forgets the session of the client and removes its cookie; the next
     * call of the client starts a new one. Its statements and prepared
     * statements keep the session.
     */
    @Override
    public void closeSession(CloseSessionRequest request, CallContext context,
            StreamListener<CloseSessionResult> listener) {
        sessions.close(context, owner(context));
        listener.onNext(new CloseSessionResult(CloseSessionResult.Status.CLOSED));
        listener.onCompleted();
    }

    @Override
    public void listFlights(CallContext context, Criteria criteria, StreamListener<FlightInfo> listener) {
        listener.onCompleted();
    }

    /** Stops forgetting handles; the queries that run go on until their clients have read them. */
    @Override
    public void close() {
        expiry.shutdownNow();
    }
}
