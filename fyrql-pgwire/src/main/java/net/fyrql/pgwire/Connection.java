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

package net.fyrql.pgwire;

import net.fyrql.FyrQL;
import net.fyrql.PreparedQuery;
import net.fyrql.QueryResult;
import net.fyrql.Session;
import net.fyrql.SqlStatement;
import net.fyrql.catalog.SearchPath;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.vector.Batch;
import net.fyrql.core.vector.Vector;
import net.fyrql.exec.QueryContext;
import net.fyrql.pgwire.Commands.Command;
import net.fyrql.pgwire.MessageReader.Body;
import net.fyrql.pgwire.MessageReader.Message;
import net.fyrql.pgwire.SqlText.Kind;
import net.fyrql.pgwire.SqlText.Token;
import net.fyrql.sql.SqlException;

import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * One connection of a client, on a thread of its own: the start with TLS
 * and the sign-in, then the simple and the extended protocol. A connection
 * has a session of the engine, whose source is the database the client
 * names; the server keeps the parameters of PostgreSQL, the state of the
 * transaction, the prepared statements, the portals and the cursors.
 * FyrQL has no transactions: {@code BEGIN}, {@code COMMIT} and
 * {@code ROLLBACK} only follow the state PostgreSQL reports, and each
 * statement writes for itself.
 */
final class Connection implements Runnable, PgSql.Context {

    private static final int SSL_REQUEST = 80877103;
    private static final int GSSENC_REQUEST = 80877104;
    private static final int CANCEL_REQUEST = 80877102;
    private static final int PROTOCOL_3 = 3 << 16;

    private final PgWireServer server;
    private final FyrQL engine;
    private final int id;
    private final int secret;
    private Socket socket;
    private MessageReader in;
    private MessageWriter out;
    private ServerSettings settings;
    private Session session;
    private String user;
    private String database;
    /** The search path as the client set it, as {@code SHOW search_path} gives it. */
    private String searchPath;
    /** {@code I} outside a transaction, {@code T} in one, {@code E} in one after an error. */
    private char status = 'I';
    private boolean wroteInTransaction;
    /** Whether the extended protocol drops the messages until {@code Sync} after an error. */
    private boolean skipping;
    private final Map<String, Prepared> statements = new HashMap<>();
    private final Map<String, Portal> portals = new HashMap<>();
    private final Map<String, Portal> cursors = new LinkedHashMap<>();
    private volatile QueryContext running;
    private volatile boolean closed;
    /** Whether the server counts the connection among those it admitted. */
    volatile boolean admitted;

    Connection(PgWireServer server, Socket socket, int id, int secret) {
        this.server = server;
        this.engine = server.engine();
        this.socket = socket;
        this.id = id;
        this.secret = secret;
    }

    int id() {
        return id;
    }

    /** Cancels the statement that runs now, if the secret is that of the connection. */
    void cancel(int key) {
        QueryContext r = running;
        if (key == secret && r != null) {
            r.cancel();
        }
    }

    /** Closes the connection from another thread, as the server does when it stops. */
    void close() {
        closed = true;
        QueryContext r = running;
        if (r != null) {
            r.cancel();
        }
        try {
            socket.close();
        } catch (IOException e) {
            // Closed already.
        }
    }

    @Override
    public void run() {
        try {
            in = new MessageReader(socket.getInputStream());
            out = new MessageWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            if (startup()) {
                loop();
            }
        } catch (EOFException | SocketException e) {
            // The client went away.
        } catch (IOException e) {
            if (!closed) {
                fatal(PgException.PROTOCOL_VIOLATION, e.getMessage());
            }
        } catch (RuntimeException e) {
            if (!closed) {
                fatal("XX000", String.valueOf(e.getMessage()));
            }
        } finally {
            closeAll();
            server.remove(this);
            try {
                socket.close();
            } catch (IOException e) {
                // Closed already.
            }
        }
    }

    /** Sends a fatal error, after which the connection ends. */
    private void fatal(String state, String message) {
        try {
            PgErrors.write(out, 'E', new PgErrors.Error("FATAL", state, message, 0));
            out.flush();
        } catch (IOException | RuntimeException e) {
            // The connection is gone.
        }
    }

    // ------------------------------------------------------------- startup

    /** Reads the startup packet and signs the client in; {@code false} if the connection ends. */
    private boolean startup() throws IOException {
        boolean tls = false;
        Body body;
        int code;
        while (true) {
            body = in.startup();
            code = body.int32();
            if (code == SSL_REQUEST) {
                if (server.tls() != null && !tls) {
                    out.raw('S');
                    socket = server.tls().wrap(socket);
                    in = new MessageReader(socket.getInputStream());
                    out = new MessageWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                    tls = true;
                } else {
                    out.raw('N');
                }
            } else if (code == GSSENC_REQUEST) {
                out.raw('N');
            } else if (code == CANCEL_REQUEST) {
                int pid = body.int32();
                int key = body.int32();
                server.cancel(pid, key);
                return false;
            } else if (code >> 16 != 3) {
                fatal(PgException.FEATURE_NOT_SUPPORTED, "unsupported frontend protocol " + (code >> 16) + "."
                        + (code & 0xFFFF) + ": server supports 3.0");
                return false;
            } else {
                break;
            }
        }
        int minor = code & 0xFFFF;
        Map<String, String> parameters = new LinkedHashMap<>();
        List<String> unknownOptions = new ArrayList<>();
        while (body.remaining() > 0) {
            String key = body.string();
            if (key.isEmpty()) {
                break;
            }
            String value = body.string();
            if (key.startsWith("_pq_.")) {
                unknownOptions.add(key);
            } else {
                parameters.put(key, value);
            }
        }
        if (server.tls() != null && !tls) {
            fatal(PgException.INVALID_AUTHORIZATION, "the server takes only connections with TLS; connect with"
                    + " sslmode=require");
            return false;
        }
        if (minor > 0 || !unknownOptions.isEmpty()) {
            // The server speaks 3.0 and no protocol options.
            out.begin('v').int32(PROTOCOL_3).int32(unknownOptions.size());
            for (String o : unknownOptions) {
                out.string(o);
            }
            out.end();
        }
        user = parameters.getOrDefault("user", "");
        if (user.isEmpty()) {
            fatal(PgException.INVALID_AUTHORIZATION, "no PostgreSQL user name specified in startup packet");
            return false;
        }
        if (!signIn()) {
            return false;
        }
        if (!server.admit(this)) {
            fatal(PgException.TOO_MANY_CONNECTIONS, "sorry, too many clients already");
            return false;
        }
        settings = new ServerSettings(server.config().serverVersion(), server.config().versionNumber(), user,
                server.config().maxConnections());
        try {
            open(parameters.get("database"));
            for (Map.Entry<String, String> p : parameters.entrySet()) {
                applyStartup(p.getKey(), p.getValue());
            }
        } catch (PgException e) {
            fatal(e.state(), e.getMessage());
            return false;
        } catch (ServerSettings.InvalidSetting e) {
            fatal(e.state(), e.getMessage());
            return false;
        } catch (RuntimeException e) {
            PgErrors.Error error = PgErrors.of(e, null);
            fatal(error.state(), error.message());
            return false;
        }
        out.begin('R').int32(0).end();
        for (String name : ServerSettings.REPORTED) {
            parameterStatus(name);
        }
        out.begin('K').int32(id).int32(secret).end();
        ready();
        return true;
    }

    /** Signs the client in with SCRAM-SHA-256 if the server has users; {@code false} if it fails. */
    private boolean signIn() throws IOException {
        if (!server.config().signIn()) {
            return true;
        }
        out.begin('R').int32(10).string(Scram.MECHANISM).int8(0).end();
        out.flush();
        Scram.Exchange exchange = server.scram().begin(user);
        try {
            Message m = in.next();
            if (m.type() != 'p') {
                throw new Scram.Failure("expected SASL response, got message type " + m.type(), true);
            }
            String mechanism = m.body().string();
            if (!mechanism.equals(Scram.MECHANISM)) {
                throw new Scram.Failure("client selected an invalid SASL authentication mechanism", true);
            }
            int length = m.body().int32();
            String first = new String(m.body().bytes(length), StandardCharsets.UTF_8);
            out.begin('R').int32(11).bytes(exchange.serverFirst(first).getBytes(StandardCharsets.UTF_8)).end();
            out.flush();
            m = in.next();
            if (m.type() != 'p') {
                throw new Scram.Failure("expected SASL response, got message type " + m.type(), true);
            }
            String last = new String(m.body().bytes(m.body().remaining()), StandardCharsets.UTF_8);
            out.begin('R').int32(12).bytes(exchange.serverFinal(last).getBytes(StandardCharsets.UTF_8)).end();
            return true;
        } catch (Scram.Failure f) {
            fatal(f.malformed() ? PgException.PROTOCOL_VIOLATION : PgException.INVALID_PASSWORD,
                    f.malformed() ? f.getMessage() : "password authentication failed for user \"" + user + "\"");
            return false;
        }
    }

    /**
     * Opens the session on the database the client names, a source of the
     * engine; without one, or with the name of the user as psql sends it by
     * default, on the default source of the engine.
     *
     * @throws PgException if there is no such database
     */
    private void open(String name) {
        List<String> sources = engine.sources();
        SearchPath engineDefault = engine.catalog().searchPath();
        String source = name == null || name.isEmpty() ? null : match(sources, name);
        if (source == null && name != null && !name.isEmpty() && !name.equals(user)) {
            throw new PgException(PgException.INVALID_CATALOG, "database \"" + name + "\" does not exist");
        }
        if (source == null) {
            source = engineDefault.defaultSource() != null ? engineDefault.defaultSource()
                    : sources.isEmpty() ? null : sources.get(0);
        }
        database = source;
        session = server.newSession(source, user, new PgFunctions.Lookups() {
            @Override
            public Integer relation(String table) {
                try {
                    return Connection.this.relation(table);
                } catch (PgException e) {
                    return null;
                }
            }

            @Override
            public List<String> columns(int oid) {
                return server.catalog().snapshot(database).columns().getOrDefault(oid, List.of());
            }
        });
        if (source != null) {
            String schema = source.equals(engineDefault.defaultSource()) ? engineDefault.defaultSchema()
                    : defaultSchema(source);
            session.use(new SearchPath(source, schema));
            searchPath = schema;
        } else {
            searchPath = "";
        }
    }

    /** The schema a database starts with: {@code main}, else {@code public}, else its first. */
    private String defaultSchema(String source) {
        List<String> schemas = engine.connector(source).catalog().schemas();
        for (String preferred : List.of("main", "public")) {
            if (schemas.contains(preferred)) {
                return preferred;
            }
        }
        return schemas.isEmpty() ? "main" : schemas.get(0);
    }

    private static String match(List<String> names, String name) {
        if (names.contains(name)) {
            return name;
        }
        String found = null;
        for (String n : names) {
            if (n.equalsIgnoreCase(name)) {
                if (found != null) {
                    return null;
                }
                found = n;
            }
        }
        return found;
    }

    /** Applies a parameter of the startup packet, as {@code SET} would. */
    private void applyStartup(String name, String value) {
        switch (name) {
            case "user", "database", "replication" -> {
                if (name.equals("replication") && !value.equalsIgnoreCase("false")) {
                    throw new PgException(PgException.FEATURE_NOT_SUPPORTED, "FyrQL does not replicate");
                }
            }
            case "options" -> {
                // -c name=value, as psql passes PGOPTIONS.
                String[] parts = value.trim().split("\\s+");
                for (int i = 0; i < parts.length; i++) {
                    String p = parts[i];
                    String setting = p.equals("-c") && i + 1 < parts.length ? parts[++i]
                            : p.startsWith("-c") ? p.substring(2) : p.startsWith("--") ? p.substring(2) : null;
                    int eq = setting == null ? -1 : setting.indexOf('=');
                    if (eq > 0) {
                        applyStartup(setting.substring(0, eq).replace('-', '_'), setting.substring(eq + 1));
                    }
                }
            }
            default -> set(name, value == null ? null : List.of(value));
        }
    }

    // ---------------------------------------------------------------- loop

    private void loop() throws IOException {
        while (!closed) {
            Message m = in.next();
            if (skipping && m.type() != 'S' && m.type() != 'X') {
                continue;
            }
            switch (m.type()) {
                case 'Q' -> simpleQuery(m.body().string(charset()));
                case 'P' -> extended(() -> parse(m.body()));
                case 'B' -> extended(() -> bind(m.body()));
                case 'D' -> extended(() -> describe(m.body()));
                case 'E' -> extended(() -> execute(m.body()));
                case 'C' -> extended(() -> close(m.body()));
                case 'S' -> sync();
                case 'H' -> out.flush();
                case 'X' -> {
                    return;
                }
                case 'd', 'c', 'f' -> {
                    // Data of a COPY the server never started.
                }
                case 'F' -> extended(() -> {
                    throw new PgException(PgException.FEATURE_NOT_SUPPORTED, "Not supported in FyrQL: the function"
                            + " call of the protocol");
                });
                default -> {
                    fatal(PgException.PROTOCOL_VIOLATION, "invalid frontend message type " + (int) m.type());
                    return;
                }
            }
        }
    }

    private Charset charset() {
        return settings.charset();
    }

    private ZoneId zone() {
        return session.timeZone();
    }

    /** A step of the extended protocol, which may fail. */
    @FunctionalInterface
    private interface Step {
        void run() throws IOException;
    }

    /** Runs a step of the extended protocol; after an error, the messages until {@code Sync} are dropped. */
    private void extended(Step step) throws IOException {
        try {
            step.run();
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            error(e, null);
            skipping = true;
        }
    }

    private void sync() throws IOException {
        skipping = false;
        if (status == 'I') {
            closePortals();
        }
        ready();
    }

    private void ready() throws IOException {
        out.begin('Z').int8(status).end();
        out.flush();
    }

    /** Sends an error and marks a transaction as failed. */
    private void error(Throwable e, String sql) throws IOException {
        PgErrors.write(out, 'E', e instanceof Located l ? PgErrors.of(l.getCause(), l.sql) : PgErrors.of(e, sql));
        if (status == 'T') {
            status = 'E';
        }
    }

    private void notice(String severity, String state, String message) throws IOException {
        PgErrors.write(out, 'N', new PgErrors.Error(severity, state, message, 0));
    }

    private void parameterStatus(String name) throws IOException {
        String value = name.equals(ServerSettings.TIME_ZONE) ? session.setting(ServerSettings.TIME_ZONE)
                : settings.find(name);
        if (value != null) {
            out.begin('S').string(name).string(value).end();
        }
    }

    // -------------------------------------------------------- simple query

    private void simpleQuery(String text) throws IOException {
        List<String> parts;
        try {
            parts = SqlText.split(text);
        } catch (RuntimeException e) {
            error(e, text);
            ready();
            return;
        }
        if (parts.isEmpty()) {
            out.begin('I').end();
            ready();
            return;
        }
        for (String sql : parts) {
            try {
                Command command = Commands.parse(sql);
                Prepared p = new Prepared(sql, command, null, new int[0], null);
                Portal portal = new Portal(p, List.of(), new short[0]);
                try {
                    run(portal, 0, true);
                } finally {
                    portal.close();
                }
            } catch (RuntimeException e) {
                error(e, sql);
                break;
            }
        }
        ready();
    }

    // ----------------------------------------------------------- statements

    /**
     * A statement of the extended protocol, or one of a simple query.
     *
     * @param query the statement prepared by the engine, or {@code null} for one of the server
     * @param oids the types of the parameters
     * @param sql the text the engine prepared, for the positions of errors
     */
    private record Prepared(String text, Command command, PreparedQuery query, int[] oids, String sql) {
    }

    /** Rows to send: of the engine, or of the server. */
    private interface Rows extends AutoCloseable {
        Schema schema();

        Batch next();

        @Override
        void close();
    }

    /** A statement with the values of its parameters, run once or in parts. */
    private final class Portal {
        final Prepared statement;
        final List<Object> values;
        final short[] formats;
        Rows rows;
        Batch batch;
        int row;
        boolean started;
        boolean finished;
        String tag;
        /** Whether the portal holds a place among the queries that run at once. */
        boolean permit;
        /** Whether the portal is a cursor that outlives its transaction. */
        boolean hold;
        /** The context of the query of the portal, which a cancel request cancels. */
        QueryContext context;

        Portal(Prepared statement, List<Object> values, short[] formats) {
            this.statement = statement;
            this.values = values;
            this.formats = formats;
        }

        void close() {
            if (rows != null) {
                try {
                    rows.close();
                } catch (RuntimeException e) {
                    // The rows of a closed query.
                }
                rows = null;
            }
            if (permit) {
                server.queries().release();
                permit = false;
            }
            finished = true;
        }
    }

    // ------------------------------------------------------ extended protocol

    private void parse(Body body) throws IOException {
        String name = body.string(charset());
        String text = body.string(charset());
        int n = body.int16();
        int[] clientOids = new int[n];
        for (int i = 0; i < n; i++) {
            clientOids[i] = body.int32();
        }
        if (!name.isEmpty() && statements.containsKey(name)) {
            throw new PgException(PgException.DUPLICATE_PREPARED_STATEMENT, "prepared statement \"" + name
                    + "\" already exists");
        }
        List<String> parts = SqlText.split(text);
        if (parts.size() > 1) {
            throw new PgException(PgException.SYNTAX_ERROR, "cannot insert multiple commands into a prepared"
                    + " statement");
        }
        String sql = parts.isEmpty() ? "" : parts.get(0);
        Command command = parts.isEmpty() ? null : Commands.parse(sql);
        Prepared p;
        if (command instanceof Commands.Engine) {
            checkTransaction(command);
            String plain = PgSql.rewrite(sql, this);
            DataType[] types = new DataType[clientOids.length];
            for (int i = 0; i < types.length; i++) {
                types[i] = PgTypes.fyrqlType(clientOids[i]);
            }
            String rewritten = PgSql.typeParameters(plain, types);
            PreparedQuery query;
            try {
                query = session.prepare(rewritten);
                // A numeric of the client without a context is a decimal, not the VARCHAR of the engine.
                boolean again = false;
                for (int i = 0; i < types.length && i < query.parameterTypes().size(); i++) {
                    if (clientOids[i] == PgTypes.NUMERIC && query.parameterTypes().get(i) == DataType.VARCHAR) {
                        types[i] = DataType.decimal(38, 10);
                        again = true;
                    }
                }
                if (again) {
                    rewritten = PgSql.typeParameters(plain, types);
                    query = session.prepare(rewritten);
                }
            } catch (SqlException e) {
                throw new Located(e, rewritten);
            }
            int count = query.parameterTypes().size();
            int[] oids = new int[count];
            for (int i = 0; i < count; i++) {
                oids[i] = i < clientOids.length && clientOids[i] != 0 ? clientOids[i]
                        : PgTypes.oid(query.parameterTypes().get(i));
            }
            p = new Prepared(sql, command, query, oids, rewritten);
        } else {
            int[] oids = new int[clientOids.length];
            for (int i = 0; i < oids.length; i++) {
                oids[i] = clientOids[i] != 0 ? clientOids[i] : PgTypes.TEXT;
            }
            p = new Prepared(sql, command, null, oids, null);
        }
        statements.put(name, p);
        out.begin('1').end();
    }

    /** An error of the engine with the text it was in, for its position. */
    private static final class Located extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String sql;

        Located(RuntimeException cause, String sql) {
            super(cause.getMessage(), cause);
            this.sql = sql;
        }
    }

    private void bind(Body body) throws IOException {
        String portalName = body.string(charset());
        String statementName = body.string(charset());
        Prepared p = statements.get(statementName);
        if (p == null) {
            throw new PgException(PgException.INVALID_STATEMENT_NAME, "prepared statement \"" + statementName
                    + "\" does not exist");
        }
        int formatCount = body.int16();
        short[] parameterFormats = new short[formatCount];
        for (int i = 0; i < formatCount; i++) {
            parameterFormats[i] = body.int16();
        }
        int n = body.int16();
        if (n != p.oids().length) {
            throw new PgException(PgException.PROTOCOL_VIOLATION, "bind message supplies " + n + " parameters, but"
                    + " prepared statement \"" + statementName + "\" requires " + p.oids().length);
        }
        List<Object> values = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int length = body.int32();
            byte[] bytes = length < 0 ? null : body.bytes(length);
            short format = formatCount == 0 ? 0 : formatCount == 1 ? parameterFormats[0] : parameterFormats[i];
            values.add(bytes == null ? null : value(p, i, bytes, format));
        }
        int resultCount = body.int16();
        short[] formats = new short[resultCount];
        for (int i = 0; i < resultCount; i++) {
            formats[i] = body.int16();
        }
        Portal old = portals.remove(portalName);
        if (old != null) {
            old.close();
        } else if (!portalName.isEmpty() && cursors.containsKey(portalName)) {
            throw new PgException(PgException.DUPLICATE_CURSOR, "cursor \"" + portalName + "\" already exists");
        }
        portals.put(portalName, new Portal(p, values, formats));
        out.begin('2').end();
    }

    /** Reads the value of a parameter as a value of its type of FyrQL. */
    private Object value(Prepared p, int i, byte[] bytes, short format) {
        DataType type = p.query() == null ? DataType.VARCHAR : p.query().parameterTypes().get(i);
        try {
            if (format == 1) {
                return PgTypes.parseBinary(bytes, p.oids()[i], type, zone(), charset());
            }
            return PgTypes.parseText(new String(bytes, charset()), type, zone());
        } catch (RuntimeException e) {
            throw new PgException(format == 1 ? PgException.INVALID_BINARY_REPRESENTATION
                    : PgException.INVALID_TEXT_REPRESENTATION, "invalid value for parameter $" + (i + 1) + " of type "
                    + type.sqlName() + ": " + e.getMessage(), e);
        }
    }

    private void describe(Body body) throws IOException {
        char kind = (char) body.int8();
        String name = body.string(charset());
        if (kind == 'S') {
            Prepared p = statements.get(name);
            if (p == null) {
                throw new PgException(PgException.INVALID_STATEMENT_NAME, "prepared statement \"" + name
                        + "\" does not exist");
            }
            out.begin('t').int16(p.oids().length);
            for (int oid : p.oids()) {
                out.int32(oid);
            }
            out.end();
            rowDescription(columns(p), new short[0]);
        } else if (kind == 'P') {
            Portal portal = portals.get(name);
            Portal cursor = portal == null ? cursors.get(name) : null;
            if (cursor != null) {
                // A cursor of SQL is a portal of the protocol as well.
                rowDescription(cursor.rows == null ? null : cursor.rows.schema(), new short[0]);
                return;
            }
            if (portal == null) {
                throw new PgException(PgException.INVALID_CURSOR_NAME, "portal \"" + name + "\" does not exist");
            }
            rowDescription(columns(portal.statement), portal.formats);
        } else {
            throw new PgException(PgException.PROTOCOL_VIOLATION, "invalid DESCRIBE message subtype " + (int) kind);
        }
    }

    /** Returns the columns of the rows a statement gives, or {@code null} for none. */
    private Schema columns(Prepared p) {
        if (p.query() != null) {
            return p.query().writes() ? null : p.query().schema();
        }
        return switch (p.command()) {
            case Commands.Show s -> showSchema(s.name());
            case Commands.Fetch f when !f.move() -> {
                Portal cursor = cursors.get(f.name());
                yield cursor == null || cursor.rows == null ? null : cursor.rows.schema();
            }
            case null, default -> null;
        };
    }

    private void rowDescription(Schema schema, short[] formats) throws IOException {
        if (schema == null) {
            out.begin('n').end();
            return;
        }
        out.begin('T').int16(schema.size());
        for (int i = 0; i < schema.size(); i++) {
            Schema.Field f = schema.fields().get(i);
            PgTypes.PgType t = PgTypes.of(f.type());
            out.string(f.name()).int32(0).int16(0).int32(t.oid()).int16(t.length()).int32(t.modifier())
                    .int16(format(formats, i));
        }
        out.end();
    }

    private static short format(short[] formats, int column) {
        return formats.length == 0 ? 0 : formats.length == 1 ? formats[0] : formats[column];
    }

    private void execute(Body body) throws IOException {
        String name = body.string(charset());
        int maxRows = body.int32();
        Portal portal = portals.get(name);
        if (portal == null) {
            throw new PgException(PgException.INVALID_CURSOR_NAME, "portal \"" + name + "\" does not exist");
        }
        try {
            run(portal, maxRows <= 0 ? 0 : maxRows, false);
        } catch (RuntimeException e) {
            portal.close();
            throw e;
        }
    }

    private void close(Body body) throws IOException {
        char kind = (char) body.int8();
        String name = body.string(charset());
        if (kind == 'S') {
            statements.remove(name);
        } else if (kind == 'P') {
            Portal p = portals.remove(name);
            if (p != null) {
                p.close();
            }
        } else {
            throw new PgException(PgException.PROTOCOL_VIOLATION, "invalid CLOSE message subtype " + (int) kind);
        }
        out.begin('3').end();
    }

    // ------------------------------------------------------------- running

    /**
     * Runs a portal: a statement of the server, or one of the engine, whose
     * rows go to the client up to a number.
     *
     * @param maxRows the most rows to send, 0 for all
     * @param describe whether the rows come with their {@code RowDescription}, as in a simple query
     */
    private void run(Portal portal, long maxRows, boolean describe) throws IOException {
        try {
            runPortal(portal, maxRows, describe);
        } finally {
            running = null;
        }
    }

    private void runPortal(Portal portal, long maxRows, boolean describe) throws IOException {
        Prepared p = portal.statement;
        if (p.command() == null) {
            out.begin('I').end();
            return;
        }
        if (portal.finished && portal.started) {
            out.begin('C').string(portal.tag == null ? "SELECT 0" : portal.tag).end();
            return;
        }
        checkTransaction(p.command());
        switch (p.command()) {
            case Commands.Engine e -> runEngine(portal, maxRows, describe);
            case Commands.Transaction t -> transaction(t);
            case Commands.Set s -> {
                apply(s);
                complete(s.tag());
            }
            case Commands.Many m -> {
                for (Commands.Set s : m.sets()) {
                    apply(s);
                }
                complete("SET");
            }
            case Commands.ResetAll r -> {
                resetAll();
                complete("RESET");
            }
            case Commands.Show s -> show(s, portal, describe);
            case Commands.Declare d -> declare(d);
            case Commands.Fetch f -> fetch(f, portal, describe);
            case Commands.Close c -> {
                if (c.name() == null) {
                    closeCursors(true);
                    complete("CLOSE CURSOR ALL");
                } else {
                    Portal cursor = cursors.remove(c.name());
                    if (cursor == null) {
                        throw new PgException(PgException.INVALID_CURSOR_NAME, "cursor \"" + c.name()
                                + "\" does not exist");
                    }
                    cursor.close();
                    complete("CLOSE CURSOR");
                }
            }
            case Commands.Discard d -> {
                if (status != 'I') {
                    throw new PgException(PgException.INVALID_TRANSACTION_STATE, "DISCARD ALL cannot run inside a"
                            + " transaction block");
                }
                if (d.tag().equals("DISCARD ALL")) {
                    closeCursors(true);
                    statements.keySet().removeIf(k -> !k.isEmpty());
                    resetAll();
                }
                complete(d.tag());
            }
            case Commands.Deallocate d -> {
                if (d.name() == null) {
                    statements.keySet().removeIf(k -> !k.isEmpty());
                } else if (statements.remove(d.name()) == null) {
                    throw new PgException(PgException.INVALID_STATEMENT_NAME, "prepared statement \"" + d.name()
                            + "\" does not exist");
                }
                complete(d.name() == null ? "DEALLOCATE ALL" : "DEALLOCATE");
            }
            case Commands.Ignored i -> complete(i.tag());
            case Commands.Unsupported u -> throw new PgException(PgException.FEATURE_NOT_SUPPORTED, u.message());
        }
        portal.started = true;
        if (!(p.command() instanceof Commands.Engine) && !(p.command() instanceof Commands.Fetch)) {
            portal.finished = true;
        }
    }

    private void complete(String tag) throws IOException {
        out.begin('C').string(tag).end();
    }

    /** In a failed transaction, only its end runs, as in PostgreSQL. */
    private void checkTransaction(Command command) {
        if (status == 'E' && !(command instanceof Commands.Transaction t && (t.op() == Commands.TransactionOp.COMMIT
                || t.op() == Commands.TransactionOp.ROLLBACK || t.op() == Commands.TransactionOp.ROLLBACK_TO))) {
            throw new PgException(PgException.IN_FAILED_TRANSACTION, "current transaction is aborted, commands"
                    + " ignored until end of transaction block");
        }
    }

    private void transaction(Commands.Transaction t) throws IOException {
        switch (t.op()) {
            case BEGIN -> {
                if (status != 'I') {
                    notice("WARNING", "25001", "there is already a transaction in progress");
                } else {
                    status = 'T';
                    wroteInTransaction = false;
                }
                complete(t.tag());
            }
            case COMMIT, ROLLBACK -> {
                boolean failed = status == 'E';
                if (status == 'I') {
                    notice("WARNING", PgException.NO_ACTIVE_TRANSACTION, "there is no transaction in progress");
                } else if (t.op() == Commands.TransactionOp.ROLLBACK && wroteInTransaction) {
                    notice("WARNING", "01000", "FyrQL has no transactions: what the statements of the transaction"
                            + " wrote stays written");
                }
                status = 'I';
                wroteInTransaction = false;
                closeCursors(false);
                complete(failed ? "ROLLBACK" : t.tag());
            }
            case ROLLBACK_TO -> {
                if (status == 'I') {
                    throw new PgException(PgException.NO_ACTIVE_TRANSACTION, "ROLLBACK TO SAVEPOINT can only be"
                            + " used in transaction blocks");
                }
                status = 'T';
                complete("ROLLBACK");
            }
            case SAVEPOINT, RELEASE -> {
                if (status == 'I') {
                    throw new PgException(PgException.NO_ACTIVE_TRANSACTION, t.tag() + " can only be used in"
                            + " transaction blocks");
                }
                complete(t.tag());
            }
        }
    }

    /** Runs a statement of the engine, with a place among the queries that run at once. */
    private void runEngine(Portal portal, long maxRows, boolean describe) throws IOException {
        Prepared p = portal.statement;
        if (!portal.started) {
            if (!server.queries().tryAcquire()) {
                throw new PgException(PgException.INSUFFICIENT_RESOURCES, "The server runs " + server.maxQueries()
                        + " queries, the most it runs at once (maxQueries); try again later");
            }
            portal.permit = true;
            QueryContext context = new QueryContext();
            portal.context = context;
            running = context;
            String sql = p.sql();
            try {
                SqlStatement statement;
                if (p.query() != null) {
                    statement = session.statement(p.query(), portal.values);
                } else {
                    sql = PgSql.rewrite(p.text(), this);
                    statement = session.statement(sql);
                }
                if (!statement.isQuery()) {
                    long count;
                    try {
                        count = session.update(statement, context);
                    } finally {
                        server.catalog().invalidate(database);
                    }
                    wroteInTransaction |= status != 'I';
                    portal.started = true;
                    portal.finished = true;
                    complete(writeTag(sql, count));
                    portal.close();
                    return;
                }
                QueryResult result = session.execute(statement, context);
                portal.rows = new Rows() {
                    @Override
                    public Schema schema() {
                        return result.schema();
                    }

                    @Override
                    public Batch next() {
                        return result.nextBatch();
                    }

                    @Override
                    public void close() {
                        result.close();
                    }
                };
            } catch (SqlException e) {
                throw new Located(e, sql);
            }
            portal.started = true;
            if (describe) {
                rowDescription(portal.rows.schema(), new short[0]);
            }
        }
        running = portal.context;
        long sent = send(portal, maxRows);
        if (portal.finished) {
            complete(startsWith(p.text(), "EXPLAIN") ? "EXPLAIN" : "SELECT " + sent);
            portal.tag = "SELECT 0";
            portal.close();
        } else {
            out.begin('s').end();
        }
    }

    /** Sends rows of a portal up to a number, and marks it finished after its last row. */
    private long send(Portal portal, long maxRows) throws IOException {
        long sent = 0;
        Schema schema = portal.rows.schema();
        int n = schema.size();
        ZoneId zone = zone();
        Charset charset = charset();
        while (maxRows == 0 || sent < maxRows) {
            if (portal.batch == null || portal.row >= portal.batch.rowCount()) {
                Batch next = portal.rows.next();
                if (next == null) {
                    portal.finished = true;
                    break;
                }
                portal.batch = next;
                portal.row = 0;
                continue;
            }
            Batch b = portal.batch;
            int r = portal.row++;
            out.begin('D').int16(n);
            for (int c = 0; c < n; c++) {
                Vector v = b.column(c);
                Object value = v.get(r);
                if (value == null) {
                    out.int32(-1);
                    continue;
                }
                DataType type = schema.fields().get(c).type();
                byte[] bytes = format(portal.formats, c) == 1 ? PgTypes.binary(value, type, zone, charset)
                        : PgTypes.text(value, type, zone).getBytes(charset);
                out.int32(bytes.length).bytes(bytes);
            }
            out.end();
            sent++;
        }
        if (!portal.finished && (portal.batch == null || portal.row >= portal.batch.rowCount())) {
            // The last row was sent; see whether there are more, so that the client gets CommandComplete now.
            Batch next = portal.rows.next();
            while (next != null && next.rowCount() == 0) {
                next = portal.rows.next();
            }
            if (next == null) {
                portal.finished = true;
            } else {
                portal.batch = next;
                portal.row = 0;
            }
        }
        return sent;
    }

    private static boolean startsWith(String sql, String word) {
        for (Token t : SqlText.tokens(sql)) {
            return t.is(word);
        }
        return false;
    }

    /** The tag of a statement that writes, as PostgreSQL gives it. */
    private static String writeTag(String sql, long count) {
        List<Token> t = SqlText.tokens(sql);
        String first = t.isEmpty() ? "" : t.get(0).upper();
        return switch (first) {
            case "INSERT" -> "INSERT 0 " + count;
            case "COPY" -> "COPY " + count;
            case "CREATE" -> {
                for (Token k : t) {
                    if (k.is("AS")) {
                        yield "SELECT " + count;
                    }
                }
                yield "CREATE TABLE";
            }
            case "DROP" -> "DROP TABLE";
            case "SET", "RESET", "USE" -> "SET";
            case "UPDATE", "DELETE" -> first + " " + count;
            default -> first;
        };
    }

    // ------------------------------------------------------------ settings

    private void apply(Commands.Set s) throws IOException {
        if (s.local() && status == 'I') {
            notice("WARNING", "25P01", "SET LOCAL can only be used in transaction blocks");
            return;
        }
        List<String> values = null;
        if (s.values() != null) {
            boolean path = s.name().equalsIgnoreCase(ServerSettings.SEARCH_PATH);
            values = new ArrayList<>();
            for (Token v : s.values()) {
                // A word stays as written, except in a search path, where PostgreSQL folds it; a quoted schema
                // keeps its quotes for SHOW.
                values.add(v.kind() == Kind.WORD ? (path ? v.value() : v.text())
                        : v.kind() == Kind.QUOTED && path ? v.text() : v.value());
            }
        }
        String name = set(s.name(), values);
        if (ServerSettings.REPORTED.contains(name)) {
            parameterStatus(name);
        }
    }

    /**
     * Sets a parameter, or resets it for {@code null}.
     *
     * @return the name as PostgreSQL spells it
     */
    private String set(String name, List<String> values) {
        String value = values == null ? null : String.join(", ", values);
        if (name.equalsIgnoreCase(ServerSettings.TIME_ZONE)) {
            if (value == null) {
                session.reset(ServerSettings.TIME_ZONE);
            } else {
                session.set(ServerSettings.TIME_ZONE, value);
            }
            return ServerSettings.TIME_ZONE;
        }
        if (name.equalsIgnoreCase(ServerSettings.STATEMENT_TIMEOUT)) {
            if (value == null) {
                session.reset(ServerSettings.STATEMENT_TIMEOUT);
            } else {
                session.set(ServerSettings.STATEMENT_TIMEOUT, value);
            }
            return ServerSettings.STATEMENT_TIMEOUT;
        }
        if (name.equalsIgnoreCase(ServerSettings.SEARCH_PATH)) {
            searchPath(values);
            return ServerSettings.SEARCH_PATH;
        }
        String set = settings.set(name, value);
        if (set.equals(ServerSettings.CLIENT_ENCODING)) {
            out.charset(settings.charset());
        }
        return set;
    }

    /**
     * Sets the search path: each part is a schema of the database of the
     * connection, and the first that exists becomes the schema of the
     * session; {@code $user} and schemas that do not exist are passed over
     * as in PostgreSQL.
     */
    private void searchPath(List<String> parts) {
        if (database == null) {
            searchPath = parts == null ? "" : String.join(", ", parts);
            return;
        }
        if (parts == null) {
            SearchPath engineDefault = engine.catalog().searchPath();
            String schema = database.equals(engineDefault.defaultSource()) ? engineDefault.defaultSchema()
                    : defaultSchema(database);
            session.use(new SearchPath(database, schema));
            searchPath = schema;
            return;
        }
        List<String> schemas = engine.connector(database).catalog().schemas();
        for (String part : parts) {
            String name = unquote(part);
            String schema = name.equals("$user") ? null : match(schemas, name);
            if (schema != null) {
                session.use(new SearchPath(database, schema));
                break;
            }
        }
        searchPath = String.join(", ", parts);
    }

    private static String unquote(String part) {
        String p = part.strip();
        if (p.length() >= 2 && p.startsWith("\"") && p.endsWith("\"")) {
            return p.substring(1, p.length() - 1).replace("\"\"", "\"");
        }
        return p;
    }

    private void resetAll() {
        settings.resetAll();
        out.charset(settings.charset());
        session.reset(ServerSettings.TIME_ZONE);
        session.reset(ServerSettings.STATEMENT_TIMEOUT);
        searchPath(null);
    }

    /** The value of a parameter as {@code SHOW} gives it. */
    private String show(String name) {
        if (name.equalsIgnoreCase(ServerSettings.TIME_ZONE)) {
            return session.setting(ServerSettings.TIME_ZONE);
        }
        if (name.equalsIgnoreCase(ServerSettings.STATEMENT_TIMEOUT)) {
            return session.setting(ServerSettings.STATEMENT_TIMEOUT);
        }
        if (name.equalsIgnoreCase(ServerSettings.SEARCH_PATH)) {
            return searchPath;
        }
        return settings.get(name);
    }

    private Schema showSchema(String name) {
        if (name.equals("ALL")) {
            return Schema.of(new Schema.Field("name", DataType.VARCHAR), new Schema.Field("setting", DataType.VARCHAR),
                    new Schema.Field("description", DataType.VARCHAR));
        }
        String canonical = name.equalsIgnoreCase(ServerSettings.TIME_ZONE) ? ServerSettings.TIME_ZONE
                : name.equalsIgnoreCase(ServerSettings.SEARCH_PATH) ? ServerSettings.SEARCH_PATH
                : name.equalsIgnoreCase(ServerSettings.STATEMENT_TIMEOUT) ? ServerSettings.STATEMENT_TIMEOUT
                : settings.name(name);
        return Schema.of(new Schema.Field(canonical == null ? name.toLowerCase(Locale.ROOT) : canonical,
                DataType.VARCHAR));
    }

    private void show(Commands.Show s, Portal portal, boolean describe) throws IOException {
        Schema schema = showSchema(s.name());
        List<Object[]> rows = new ArrayList<>();
        if (s.name().equals("ALL")) {
            Map<String, String> all = settings.all();
            all.put(ServerSettings.TIME_ZONE, show(ServerSettings.TIME_ZONE));
            all.put(ServerSettings.SEARCH_PATH, show(ServerSettings.SEARCH_PATH));
            all.put(ServerSettings.STATEMENT_TIMEOUT, show(ServerSettings.STATEMENT_TIMEOUT));
            for (Map.Entry<String, String> e : new TreeMap<>(all).entrySet()) {
                rows.add(new Object[] {e.getKey(), e.getValue(), ""});
            }
        } else {
            rows.add(new Object[] {show(s.name())});
        }
        if (describe) {
            rowDescription(schema, new short[0]);
        }
        Iterator<Batch> batches = List.of(Batch.ofRows(schema, rows)).iterator();
        portal.rows = new Rows() {
            @Override
            public Schema schema() {
                return schema;
            }

            @Override
            public Batch next() {
                return batches.hasNext() ? batches.next() : null;
            }

            @Override
            public void close() {
            }
        };
        send(portal, 0);
        complete("SHOW");
    }

    // ------------------------------------------------------------- cursors

    private void declare(Commands.Declare d) throws IOException {
        if (status == 'I' && !d.hold()) {
            throw new PgException(PgException.NO_ACTIVE_TRANSACTION, "DECLARE CURSOR can only be used in transaction"
                    + " blocks");
        }
        if (cursors.containsKey(d.name()) || portals.containsKey(d.name())) {
            throw new PgException(PgException.DUPLICATE_CURSOR, "cursor \"" + d.name() + "\" already exists");
        }
        Command command = Commands.parse(d.query());
        if (!(command instanceof Commands.Engine)) {
            throw new PgException(PgException.SYNTAX_ERROR, "DECLARE CURSOR needs a query");
        }
        Prepared p = new Prepared(d.query(), command, null, new int[0], null);
        Portal cursor = new Portal(p, List.of(), new short[0]);
        cursor.hold = d.hold();
        // The query runs at once; the rows wait for FETCH.
        try {
            openCursor(cursor);
        } catch (RuntimeException e) {
            cursor.close();
            throw e;
        }
        cursors.put(d.name(), cursor);
        complete("DECLARE CURSOR");
    }

    /** Opens the rows of a cursor without sending any. */
    private void openCursor(Portal cursor) {
        Prepared p = cursor.statement;
        if (!server.queries().tryAcquire()) {
            throw new PgException(PgException.INSUFFICIENT_RESOURCES, "The server runs " + server.maxQueries()
                    + " queries, the most it runs at once (maxQueries); try again later");
        }
        cursor.permit = true;
        QueryContext context = new QueryContext();
        cursor.context = context;
        running = context;
        String sql = PgSql.rewrite(p.text(), this);
        try {
            SqlStatement statement = session.statement(sql);
            if (!statement.isQuery()) {
                throw new PgException(PgException.SYNTAX_ERROR, "DECLARE CURSOR needs a query, not a statement"
                        + " that writes");
            }
            QueryResult result = session.execute(statement, context);
            cursor.rows = new Rows() {
                @Override
                public Schema schema() {
                    return result.schema();
                }

                @Override
                public Batch next() {
                    return result.nextBatch();
                }

                @Override
                public void close() {
                    result.close();
                }
            };
            cursor.started = true;
        } catch (SqlException e) {
            throw new Located(e, sql);
        }
    }

    private void fetch(Commands.Fetch f, Portal portal, boolean describe) throws IOException {
        Portal cursor = cursors.get(f.name());
        if (cursor == null) {
            throw new PgException(PgException.INVALID_CURSOR_NAME, "cursor \"" + f.name() + "\" does not exist");
        }
        long count = f.count() == Long.MAX_VALUE ? 0 : f.count();
        if (f.move()) {
            long moved = 0;
            while ((count == 0 || moved < count) && !cursor.finished) {
                if (cursor.batch == null || cursor.row >= cursor.batch.rowCount()) {
                    Batch next = cursor.rows == null ? null : cursor.rows.next();
                    if (next == null) {
                        cursor.finished = true;
                        break;
                    }
                    cursor.batch = next;
                    cursor.row = 0;
                    continue;
                }
                cursor.row++;
                moved++;
            }
            complete("MOVE " + moved);
            return;
        }
        if (describe && cursor.rows != null) {
            rowDescription(cursor.rows.schema(), new short[0]);
        }
        running = cursor.context;
        long sent = 0;
        if (cursor.rows != null && !(cursor.finished && cursor.batch == null)) {
            Portal view = new Portal(cursor.statement, List.of(), portal.formats);
            view.rows = cursor.rows;
            view.batch = cursor.batch;
            view.row = cursor.row;
            sent = count == 0 && f.count() == 0 ? 0 : send(view, count);
            cursor.batch = view.batch;
            cursor.row = view.row;
            cursor.finished = view.finished;
        }
        complete("FETCH " + sent);
    }

    // ------------------------------------------------------------- closing

    /** Closes the cursors, or only those that end with the transaction. */
    private void closeCursors(boolean all) {
        cursors.values().removeIf(c -> {
            if (all || !c.hold) {
                c.close();
                return true;
            }
            return false;
        });
    }

    private void closePortals() {
        for (Portal p : portals.values()) {
            p.close();
        }
        portals.clear();
    }

    private void closeAll() {
        try {
            closePortals();
            closeCursors(true);
        } catch (RuntimeException e) {
            // Closing anyway.
        }
    }

    // ------------------------------------------------------- PgSql.Context

    @Override
    public String user() {
        return user;
    }

    @Override
    public String version() {
        return "PostgreSQL " + server.config().serverVersion() + " (FyrQL " + PgWireServer.VERSION + ")";
    }

    @Override
    public List<String> schemas(boolean implicit) {
        List<String> result = new ArrayList<>();
        if (implicit) {
            result.add("pg_catalog");
        }
        SearchPath path = session.searchPath();
        if (path.defaultSource() != null) {
            result.add(path.defaultSchema());
        }
        return result;
    }

    @Override
    public String setting(String name) {
        return settings.find(name);
    }

    @Override
    public int relation(String name) {
        PgCatalog.Snapshot snapshot = server.catalog().snapshot(database);
        List<String> parts = new ArrayList<>();
        for (Token t : SqlText.tokens(name)) {
            if (!t.isSymbol(".")) {
                parts.add(t.value().toLowerCase(Locale.ROOT));
            }
        }
        Integer oid = null;
        if (parts.size() == 2) {
            oid = snapshot.classes().get(parts.get(0) + "." + parts.get(1));
        } else if (parts.size() == 1) {
            List<String> path = new ArrayList<>(schemas(true));
            path.add("information_schema");
            for (String schema : path) {
                oid = snapshot.classes().get(schema.toLowerCase(Locale.ROOT) + "." + parts.get(0));
                if (oid != null) {
                    break;
                }
            }
        }
        if (oid == null) {
            throw new PgException("42P01", "relation \"" + name + "\" does not exist");
        }
        return oid;
    }

    @Override
    public int backend() {
        return id;
    }
}
