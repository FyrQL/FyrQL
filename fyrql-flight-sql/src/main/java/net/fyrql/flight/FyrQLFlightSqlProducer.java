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
import net.fyrql.arrow.ArrowToBatch;
import net.fyrql.arrow.BatchToArrow;
import net.fyrql.core.plan.PlanNode;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.exec.QueryContext;
import net.fyrql.sql.parse.Parameters;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.CancelFlightInfoRequest;
import org.apache.arrow.flight.CancelStatus;
import org.apache.arrow.flight.Criteria;
import org.apache.arrow.flight.FlightDescriptor;
import org.apache.arrow.flight.FlightEndpoint;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.PutResult;
import org.apache.arrow.flight.Result;
import org.apache.arrow.flight.SchemaResult;
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
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Answers the calls of Flight SQL with an engine of FyrQL. A query is
 * bound when a client asks for its flight info, which holds the schema of
 * the result and one ticket for this server; the ticket runs the query
 * once, and the batches go out as they come from the engine, as fast as
 * the client takes them. A prepared statement takes one row of values,
 * which keep their Arrow types as the setters of the JDBC driver keep
 * theirs. The server reads and does not write, and has no transactions.
 */
final class FyrQLFlightSqlProducer implements FlightSqlProducer {

    /** The result of {@code EXPLAIN}: the plan, one line per row. */
    private static final Schema PLAN = Schema.of(new Schema.Field("plan", DataType.VARCHAR, false));
    private static final Pattern EXPLAIN = Pattern.compile("\\s*EXPLAIN\\s+(.*)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final String READ_ONLY = "FyrQL reads and does not write";
    private static final long WAIT_MILLIS = 100;
    private static final Duration MIN_EXPIRY_PERIOD = Duration.ofSeconds(1);
    private static final Duration MAX_EXPIRY_PERIOD = Duration.ofMinutes(1);

    /** The batches of a query, or the lines of an explained plan. */
    private interface Rows extends AutoCloseable {
        /** Returns the next batch, or {@code null} after the last one. */
        Batch next();

        @Override
        void close();
    }

    /** A query the server gave out a ticket for, with the contexts of its runs. */
    private static final class Statement {
        final org.apache.arrow.vector.types.pojo.Schema schema;
        final PlanNode plan;
        final Batch lines;
        final Set<QueryContext> runs = ConcurrentHashMap.newKeySet();
        volatile boolean cancelled;

        Statement(Schema schema, PlanNode plan, Batch lines) {
            this.schema = Metadata.resultSchema(schema);
            this.plan = plan;
            this.lines = lines;
        }

        Rows open(FyrQL engine, QueryContext context) {
            if (plan == null) {
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
            QueryResult result = engine.execute(plan, context);
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

    /** A prepared statement: its query, or the query it explains, and the values bound last. */
    private static final class Prepared {
        final PreparedQuery query;
        final String explained;
        final Parameters parameters;
        final org.apache.arrow.vector.types.pojo.Schema schema;
        final org.apache.arrow.vector.types.pojo.Schema parameterSchema;
        volatile Values values;

        Prepared(PreparedQuery query, String explained) {
            this.query = query;
            this.explained = explained;
            this.parameters = query == null ? null : Parameters.parse(query.sql());
            this.schema = Metadata.resultSchema(query == null ? PLAN : query.schema());
            List<Schema.Field> fields = new ArrayList<>();
            List<DataType> types = parameterTypes();
            for (int i = 0; i < types.size(); i++) {
                fields.add(new Schema.Field("$" + (i + 1), types.get(i), true));
            }
            this.parameterSchema = Metadata.resultSchema(new Schema(fields));
        }

        List<DataType> parameterTypes() {
            return query == null ? List.of() : query.parameterTypes();
        }

        /**
         * Binds the query with the values bound last: with values of the
         * types of the parameters, or {@code NULL}, as the engine prepared
         * it; otherwise with literals of the types of the values.
         */
        Statement statement(FyrQL engine) {
            if (query == null) {
                return explain(engine, explained);
            }
            Values v = values;
            List<DataType> types = parameterTypes();
            if (v == null) {
                if (!types.isEmpty()) {
                    throw FlightErrors.invalid("The prepared statement has " + types.size() + " parameters and no"
                            + " values; bind a row of values first");
                }
                return new Statement(query.schema(), engine.plan(query, List.of()), null);
            }
            boolean fit = true;
            for (int i = 0; i < types.size() && fit; i++) {
                fit = v.values().get(i) == null || v.types().get(i).equals(types.get(i));
            }
            PlanNode plan;
            if (fit) {
                plan = engine.plan(query, v.values());
            } else {
                String[] literals = new String[types.size()];
                for (int i = 0; i < literals.length; i++) {
                    Object value = v.values().get(i);
                    literals[i] = Parameters.literal(value, value == null ? types.get(i) : v.types().get(i));
                }
                plan = engine.plan(parameters.render(literals), query.searchPath());
            }
            return new Statement(plan.schema(), plan, null);
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
    private final Handles<Statement> statements;
    private final Handles<Prepared> prepared;
    private final ScheduledExecutorService expiry;
    private final SqlInfoBuilder sqlInfo;

    /**
     * @param engine the engine that runs the queries; it stays open when the producer closes
     * @param allocator the allocator of the Arrow buffers of the results
     * @param config the handle timeout and the most queries at once
     */
    FyrQLFlightSqlProducer(FyrQL engine, BufferAllocator allocator, ServerConfig config) {
        this.engine = Objects.requireNonNull(engine, "engine");
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

    /** Returns the number of statements and prepared statements the server holds handles for. */
    int handles() {
        return statements.size() + prepared.size();
    }

    private static String owner(CallContext context) {
        return Objects.requireNonNullElse(context.peerIdentity(), "");
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

    /** Binds a query, or explains it for {@code EXPLAIN} and a query. */
    private Statement statement(String sql) {
        Matcher m = EXPLAIN.matcher(sql);
        if (m.matches()) {
            return explain(engine, m.group(1));
        }
        PlanNode plan = engine.plan(sql);
        return new Statement(plan.schema(), plan, null);
    }

    private static Statement explain(FyrQL engine, String sql) {
        List<Object[]> rows = new ArrayList<>();
        for (String line : engine.explain(sql).split("\n")) {
            rows.add(new Object[] {line});
        }
        return new Statement(PLAN, null, Batch.ofRows(PLAN, rows));
    }

    /** Runs a statement and sends its batches, unless more queries than allowed run already. */
    private void run(Statement s, ServerStreamListener listener) {
        if (s.cancelled) {
            listener.error(CallStatus.CANCELLED.withDescription("The query was canceled").toRuntimeException());
            return;
        }
        if (!queries.tryAcquire()) {
            listener.error(CallStatus.RESOURCE_EXHAUSTED.withDescription("The server runs " + maxQueries
                    + " queries, the most it runs at once (maxQueries); try again later").toRuntimeException());
            return;
        }
        QueryContext context = new QueryContext();
        s.runs.add(context);
        try {
            Backpressure ready = new Backpressure(listener, context);
            if (s.cancelled) {
                context.cancel();
            }
            try (BufferAllocator a = allocator.newChildAllocator("query", 0, Long.MAX_VALUE);
                    Rows rows = s.open(engine, context);
                    VectorSchemaRoot root = VectorSchemaRoot.create(s.schema, a)) {
                listener.start(root);
                for (Batch b = rows.next(); b != null; b = rows.next()) {
                    BatchToArrow.fill(b, root);
                    if (!ready.await()) {
                        return;
                    }
                    if (context.isCancelled()) {
                        listener.error(CallStatus.CANCELLED.withDescription(context.reason().message())
                                .toRuntimeException());
                        return;
                    }
                    listener.putNext();
                }
                listener.completed();
            }
        } catch (RuntimeException e) {
            if (!listener.isCancelled()) {
                listener.error(FlightErrors.of(e));
            }
        } finally {
            s.runs.remove(context);
            queries.release();
        }
    }

    // ---------------------------------------------------------------- statements

    @Override
    public FlightInfo getFlightInfoStatement(FlightSql.CommandStatementQuery command, CallContext context,
            FlightDescriptor descriptor) {
        try {
            noTransaction(command.hasTransactionId());
            Statement s = statement(command.getQuery());
            ByteString handle = statements.add(owner(context), s);
            return info(s.schema, descriptor, ticket(FlightSql.TicketStatementQuery.newBuilder()
                    .setStatementHandle(handle).build()));
        } catch (RuntimeException e) {
            throw FlightErrors.of(e);
        }
    }

    @Override
    public SchemaResult getSchemaStatement(FlightSql.CommandStatementQuery command, CallContext context,
            FlightDescriptor descriptor) {
        try {
            noTransaction(command.hasTransactionId());
            return new SchemaResult(statement(command.getQuery()).schema);
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

    @Override
    public Runnable acceptPutStatement(FlightSql.CommandStatementUpdate command, CallContext context,
            FlightStream flightStream, StreamListener<PutResult> ackStream) {
        return () -> ackStream.onError(FlightErrors.unimplemented(READ_ONLY));
    }

    // ---------------------------------------------------------------- prepared statements

    @Override
    public void createPreparedStatement(FlightSql.ActionCreatePreparedStatementRequest request, CallContext context,
            StreamListener<Result> listener) {
        try {
            noTransaction(request.hasTransactionId());
            Matcher m = EXPLAIN.matcher(request.getQuery());
            Prepared p;
            if (m.matches()) {
                // Explains the query now, so that a query that does not bind fails here.
                explain(engine, m.group(1));
                p = new Prepared(null, m.group(1));
            } else {
                p = new Prepared(engine.prepare(request.getQuery()), null);
            }
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
                                + rows + " or more");
                    }
                    List<Field> fields = root.getSchema().getFields();
                    if (fields.size() != parameterTypes.size()) {
                        throw FlightErrors.invalid("The query has " + parameterTypes.size() + " parameters, not "
                                + fields.size() + " values");
                    }
                    Batch batch = ArrowToBatch.batch(root, flightStream.getDictionaryProvider());
                    Object[] row = batch.row(0);
                    List<DataType> types = new ArrayList<>(row.length);
                    for (int i = 0; i < row.length; i++) {
                        boolean untyped = fields.get(i).getType() instanceof ArrowType.Null;
                        types.add(untyped ? parameterTypes.get(i) : batch.schema().type(i));
                    }
                    bound = new Values(Arrays.asList(row), types);
                }
                if (bound == null && !parameterTypes.isEmpty()) {
                    throw FlightErrors.invalid("The query has " + parameterTypes.size() + " parameters, not 0"
                            + " values");
                }
                p.values = bound;
                byte[] result = FlightSql.DoPutPreparedStatementResult.newBuilder().setPreparedStatementHandle(handle)
                        .build().toByteArray();
                try (ArrowBuf buffer = allocator.buffer(result.length)) {
                    buffer.writeBytes(result);
                    ackStream.onNext(PutResult.metadata(buffer));
                }
                ackStream.onCompleted();
            } catch (RuntimeException e) {
                ackStream.onError(FlightErrors.of(e));
            }
        };
    }

    @Override
    public FlightInfo getFlightInfoPreparedStatement(FlightSql.CommandPreparedStatementQuery command,
            CallContext context, FlightDescriptor descriptor) {
        try {
            Statement s = prepared.get(command.getPreparedStatementHandle(), owner(context)).statement(engine);
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
            s = prepared.get(command.getPreparedStatementHandle(), owner(context)).statement(engine);
        } catch (RuntimeException e) {
            listener.error(FlightErrors.of(e));
            return;
        }
        run(s, listener);
    }

    @Override
    public Runnable acceptPutPreparedStatementUpdate(FlightSql.CommandPreparedStatementUpdate command,
            CallContext context, FlightStream flightStream, StreamListener<PutResult> ackStream) {
        return () -> ackStream.onError(FlightErrors.unimplemented(READ_ONLY));
    }

    // ---------------------------------------------------------------- metadata

    private void send(org.apache.arrow.vector.types.pojo.Schema schema, ServerStreamListener listener,
            Supplier<List<Object[]>> rows) {
        try {
            Metadata.send(schema, rows.get(), allocator, listener);
        } catch (RuntimeException e) {
            listener.error(FlightErrors.of(e));
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
