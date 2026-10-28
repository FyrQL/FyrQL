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
import net.fyrql.catalog.Views;
import net.fyrql.connector.BatchReader;
import net.fyrql.connector.Catalog;
import net.fyrql.connector.Connector;
import net.fyrql.connector.ScanPlan;
import net.fyrql.connector.ScanRequest;
import net.fyrql.connector.Split;
import net.fyrql.connector.memory.MemoryConnector;
import net.fyrql.core.plan.TableRef;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.sql.bind.ViewDefinition;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

/**
 * The catalogs {@code pg_catalog} and {@code information_schema} that the
 * clients of PostgreSQL read, as tables of memory that the server builds
 * for each database from the catalog of its source: its schemas as
 * {@code pg_namespace}, its tables and columns as {@code pg_class},
 * {@code pg_attribute}, {@code tables} and {@code columns}, the types of
 * PostgreSQL as {@code pg_type}, the sources as {@code pg_database}, the
 * primary keys the sources tell as a unique index {@code table_pkey} in
 * {@code pg_class} and {@code pg_index} and a constraint of that name in
 * {@code pg_constraint}, {@code table_constraints} and
 * {@code key_column_usage}. The tables have the columns of PostgreSQL 18;
 * those FyrQL has nothing for, such as other indexes, foreign keys and
 * triggers, are empty. The OIDs are
 * stable: the catalogs and types have those of PostgreSQL, the objects of
 * the sources numbers from a hash of their names. A snapshot is at most two
 * seconds old, and new after a statement of the server that writes.
 */
final class PgCatalog {

    /** How long a snapshot of a database lasts. */
    private static final long LIFETIME_NANOS = 2_000_000_000L;

    static final int PG_CATALOG_NAMESPACE = 11;
    static final int INFORMATION_SCHEMA_NAMESPACE = 13_000;
    static final int PUBLIC_NAMESPACE = 2200;
    static final int OWNER = 10;
    /** The first OID of the objects of the sources, as for the objects of users in PostgreSQL. */
    static final int FIRST_OID = 16_384;

    static final int PG_CLASS = 1259;
    static final int PG_NAMESPACE = 2615;
    static final int PG_PROC = 1255;
    static final int PG_TYPE = 1247;
    static final int PG_DATABASE = 1262;

    private final FyrQL engine;
    private final ServerSettings defaults;
    private final List<String> functions;
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();

    /**
     * @param defaults the settings a connection starts with, for {@code pg_settings}
     * @param functions the names of the functions of the server, for {@code pg_proc}
     */
    PgCatalog(FyrQL engine, ServerSettings defaults, List<String> functions) {
        this.engine = engine;
        this.defaults = defaults;
        this.functions = List.copyOf(functions);
    }

    /**
     * The catalogs of a database at one time.
     *
     * @param classes the OIDs of the tables by their schema and name in lower case, {@code schema.table}
     * @param namespaces the OIDs of the schemas by their names
     * @param views the texts of the queries of the views by their OIDs
     * @param constraints the definitions of the primary keys by the OIDs of their constraints,
     *        as {@code pg_get_constraintdef} gives them
     * @param indexes the columns of the primary keys by the OIDs of their indexes
     * @param indexDefinitions the definitions of the indexes by their OIDs, as {@code pg_get_indexdef} gives them
     */
    record Snapshot(long built, MemoryConnector pgCatalog, MemoryConnector informationSchema,
            Map<String, Integer> classes, Map<String, Integer> namespaces, Map<Integer, String> classNames,
            Map<Integer, List<String>> columns, Map<Integer, String> views, Map<Integer, String> constraints,
            Map<Integer, List<String>> indexes, Map<Integer, String> indexDefinitions) {
    }

    /** Returns the current snapshot of a database, built now if the last is too old. */
    Snapshot snapshot(String database) {
        long now = System.nanoTime();
        Snapshot s = snapshots.get(database == null ? "" : database);
        if (s == null || now - s.built() > LIFETIME_NANOS) {
            s = build(database, now);
            snapshots.put(database == null ? "" : database, s);
        }
        return s;
    }

    /** Drops the snapshot of a database, after a statement that wrote into it. */
    void invalidate(String database) {
        snapshots.remove(database == null ? "" : database);
    }

    /** Returns the sources by which a session sees the catalogs of a database. */
    Map<String, Connector> overlay(String database) {
        Map<String, Connector> m = new LinkedHashMap<>();
        m.put("pg_catalog", new View("pg_catalog", database, true));
        m.put("information_schema", new View("information_schema", database, false));
        return m;
    }

    /** A source that reads the current snapshot of a database. */
    private final class View implements Connector {
        private final String name;
        private final String database;
        private final boolean pg;

        View(String name, String database, boolean pg) {
            this.name = name;
            this.database = database;
            this.pg = pg;
        }

        private MemoryConnector current() {
            Snapshot s = snapshot(database);
            return pg ? s.pgCatalog() : s.informationSchema();
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Catalog catalog() {
            return current().catalog();
        }

        @Override
        public ScanPlan plan(TableRef table, ScanRequest request) {
            return current().plan(table, request);
        }

        @Override
        public List<Split> splits(ScanPlan plan) {
            return current().splits(plan);
        }

        @Override
        public BatchReader open(Split split) {
            return current().open(split);
        }
    }

    // --------------------------------------------------------------- OIDs

    /** A number from the hash of a name, from {@link #FIRST_OID} to the largest {@code int}. */
    static int hashOid(String name) {
        CRC32 crc = new CRC32();
        crc.update(name.getBytes(StandardCharsets.UTF_8));
        long h = crc.getValue() * 0x9E3779B97F4A7C15L >>> 33;
        return (int) (FIRST_OID + h % ((long) Integer.MAX_VALUE - FIRST_OID));
    }

    /** Gives names OIDs from their hashes, in the order of the names, the next free one where two meet. */
    private static Map<String, Integer> oids(List<String> names, Set<Integer> taken) {
        Map<String, Integer> result = new LinkedHashMap<>();
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(null);
        for (String n : sorted) {
            int oid = hashOid(n);
            while (!taken.add(oid)) {
                oid = oid == Integer.MAX_VALUE ? FIRST_OID : oid + 1;
            }
            result.put(n, oid);
        }
        return result;
    }

    /** The OID of a source as a database. */
    static int databaseOid(String source) {
        return hashOid("database " + source);
    }

    // ------------------------------------------------------------- tables

    /** The definition of a table of a catalog: its name, OID and columns as {@code name type, ...}. */
    record Table(String name, int oid, boolean view, Schema schema) {
    }

    private static Table table(String name, int oid, boolean view, String columns) {
        List<Schema.Field> fields = new ArrayList<>();
        for (String c : columns.split(",")) {
            String[] p = c.strip().split("\\s+", 2);
            fields.add(new Schema.Field(p[0], type(p[1])));
        }
        return new Table(name, oid, view, new Schema(fields));
    }

    /** The type of FyrQL of a column of a type of PostgreSQL in a catalog. */
    static DataType type(String pg) {
        return switch (pg) {
            case "oid", "xid", "cid", "regclass", "regtype", "int4", "cardinal_number" -> DataType.INTEGER;
            case "int2" -> DataType.SMALLINT;
            case "int8" -> DataType.BIGINT;
            case "float4" -> DataType.FLOAT;
            case "float8" -> DataType.DOUBLE;
            case "bool" -> DataType.BOOLEAN;
            case "timestamptz" -> DataType.TIMESTAMPTZ;
            case "bytea" -> DataType.BLOB;
            case "name", "text", "char", "regproc", "pg_node_tree", "anyarray", "sql_identifier", "character_data",
                    "yes_or_no" -> DataType.VARCHAR;
            case "aclitem[]", "text[]", "name[]", "char[]" -> DataType.listOf(DataType.VARCHAR);
            case "oid[]", "oidvector" -> DataType.listOf(DataType.INTEGER);
            case "int2[]", "int2vector" -> DataType.listOf(DataType.SMALLINT);
            default -> throw new IllegalArgumentException("No type for " + pg);
        };
    }

    /** The tables of {@code pg_catalog}, with the OIDs of PostgreSQL. */
    static final List<Table> PG_TABLES = List.of(
            table("pg_namespace", PG_NAMESPACE, false, "oid oid, nspname name, nspowner oid, nspacl aclitem[]"),
            table("pg_class", PG_CLASS, false, "oid oid, relname name, relnamespace oid, reltype oid, reloftype oid,"
                    + " relowner oid, relam oid, relfilenode oid, reltablespace oid, relpages int4, reltuples float4,"
                    + " relallvisible int4, relallfrozen int4, reltoastrelid oid, relhasindex bool, relisshared bool,"
                    + " relpersistence char, relkind char, relnatts int2, relchecks int2, relhasrules bool,"
                    + " relhastriggers bool, relhassubclass bool, relrowsecurity bool, relforcerowsecurity bool,"
                    + " relispopulated bool, relreplident char, relispartition bool, relrewrite oid,"
                    + " relfrozenxid xid, relminmxid xid, relacl aclitem[], reloptions text[],"
                    + " relpartbound pg_node_tree"),
            table("pg_attribute", 1249, false, "attrelid oid, attname name, atttypid oid, attlen int2, attnum int2,"
                    + " atttypmod int4, attndims int2, attbyval bool, attalign char, attstorage char,"
                    + " attcompression char, attnotnull bool, atthasdef bool, atthasmissing bool, attidentity char,"
                    + " attgenerated char, attisdropped bool, attislocal bool, attinhcount int2, attcollation oid,"
                    + " attstattarget int2, attacl aclitem[], attoptions text[], attfdwoptions text[],"
                    + " attmissingval anyarray"),
            table("pg_type", PG_TYPE, false, "oid oid, typname name, typnamespace oid, typowner oid, typlen int2,"
                    + " typbyval bool, typtype char, typcategory char, typispreferred bool, typisdefined bool,"
                    + " typdelim char, typrelid oid, typsubscript regproc, typelem oid, typarray oid,"
                    + " typinput regproc, typoutput regproc, typreceive regproc, typsend regproc, typmodin regproc,"
                    + " typmodout regproc, typanalyze regproc, typalign char, typstorage char, typnotnull bool,"
                    + " typbasetype oid, typtypmod int4, typndims int4, typcollation oid,"
                    + " typdefaultbin pg_node_tree, typdefault text, typacl aclitem[]"),
            table("pg_database", PG_DATABASE, false, "oid oid, datname name, datdba oid, encoding int4,"
                    + " datlocprovider char, datistemplate bool, datallowconn bool, dathasloginevt bool,"
                    + " datconnlimit int4, datfrozenxid xid, datminmxid xid, dattablespace oid, datcollate text,"
                    + " datctype text, datlocale text, daticurules text, datcollversion text, datacl aclitem[]"),
            table("pg_description", 2609, false, "objoid oid, classoid oid, objsubid int4, description text"),
            table("pg_shdescription", 2396, false, "objoid oid, classoid oid, description text"),
            table("pg_proc", PG_PROC, false, "oid oid, proname name, pronamespace oid, proowner oid, prolang oid,"
                    + " procost float4, prorows float4, provariadic oid, prosupport regproc, prokind char,"
                    + " prosecdef bool, proleakproof bool, proisstrict bool, proretset bool, provolatile char,"
                    + " proparallel char, pronargs int2, pronargdefaults int2, prorettype oid, proargtypes oidvector,"
                    + " proallargtypes oid[], proargmodes char[], proargnames text[], proargdefaults pg_node_tree,"
                    + " protrftypes oid[], prosrc text, probin text, prosqlbody pg_node_tree, proconfig text[],"
                    + " proacl aclitem[]"),
            table("pg_roles", 12_001, true, "rolname name, rolsuper bool, rolinherit bool, rolcreaterole bool,"
                    + " rolcreatedb bool, rolcanlogin bool, rolreplication bool, rolconnlimit int4,"
                    + " rolpassword text, rolvaliduntil timestamptz, rolbypassrls bool, rolconfig text[], oid oid"),
            table("pg_user", 12_002, true, "usename name, usesysid oid, usecreatedb bool, usesuper bool,"
                    + " userepl bool, usebypassrls bool, passwd text, valuntil timestamptz, useconfig text[]"),
            table("pg_auth_members", 1261, false, "oid oid, roleid oid, member oid, grantor oid, admin_option bool,"
                    + " inherit_option bool, set_option bool"),
            table("pg_am", 2601, false, "oid oid, amname name, amhandler regproc, amtype char"),
            table("pg_tablespace", 1213, false, "oid oid, spcname name, spcowner oid, spcacl aclitem[],"
                    + " spcoptions text[]"),
            table("pg_language", 2612, false, "oid oid, lanname name, lanowner oid, lanispl bool,"
                    + " lanpltrusted bool, lanplcallfoid oid, laninline oid, lanvalidator oid, lanacl aclitem[]"),
            table("pg_collation", 3456, false, "oid oid, collname name, collnamespace oid, collowner oid,"
                    + " collprovider char, collisdeterministic bool, collencoding int4, collcollate text,"
                    + " collctype text, colllocale text, collicurules text, collversion text"),
            table("pg_settings", 12_003, true, "name text, setting text, unit text, category text, short_desc text,"
                    + " extra_desc text, context text, vartype text, source text, min_val text, max_val text,"
                    + " enumvals text[], boot_val text, reset_val text, sourcefile text, sourceline int4,"
                    + " pending_restart bool"),
            table("pg_tables", 12_004, true, "schemaname name, tablename name, tableowner name, tablespace name,"
                    + " hasindexes bool, hasrules bool, hastriggers bool, rowsecurity bool"),
            table("pg_views", 12_005, true, "schemaname name, viewname name, viewowner name, definition text"),
            table("pg_matviews", 12_006, true, "schemaname name, matviewname name, matviewowner name,"
                    + " tablespace name, hasindexes bool, ispopulated bool, definition text"),
            table("pg_stat_user_tables", 12_007, true, "relid oid, schemaname name, relname name, seq_scan int8,"
                    + " last_seq_scan timestamptz, seq_tup_read int8, idx_scan int8, last_idx_scan timestamptz,"
                    + " idx_tup_fetch int8, n_tup_ins int8, n_tup_upd int8, n_tup_del int8, n_tup_hot_upd int8,"
                    + " n_tup_newpage_upd int8, n_live_tup int8, n_dead_tup int8, n_mod_since_analyze int8,"
                    + " n_ins_since_vacuum int8, last_vacuum timestamptz, last_autovacuum timestamptz,"
                    + " last_analyze timestamptz, last_autoanalyze timestamptz, vacuum_count int8,"
                    + " autovacuum_count int8, analyze_count int8, autoanalyze_count int8"),
            table("pg_index", 2610, false, "indexrelid oid, indrelid oid, indnatts int2, indnkeyatts int2,"
                    + " indisunique bool, indnullsnotdistinct bool, indisprimary bool, indisexclusion bool,"
                    + " indimmediate bool, indisclustered bool, indisvalid bool, indcheckxmin bool, indisready bool,"
                    + " indislive bool, indisreplident bool, indkey int2vector, indcollation oidvector,"
                    + " indclass oidvector, indoption int2vector, indexprs pg_node_tree, indpred pg_node_tree"),
            table("pg_constraint", 2606, false, "oid oid, conname name, connamespace oid, contype char,"
                    + " condeferrable bool, condeferred bool, conenforced bool, convalidated bool, conrelid oid,"
                    + " contypid oid, conindid oid, conparentid oid, confrelid oid, confupdtype char,"
                    + " confdeltype char, confmatchtype char, conislocal bool, coninhcount int2, connoinherit bool,"
                    + " conperiod bool, conkey int2[], confkey int2[], conpfeqop oid[], conppeqop oid[],"
                    + " conffeqop oid[], confdelsetcols int2[], conexclop oid[], conbin pg_node_tree"),
            table("pg_attrdef", 2604, false, "oid oid, adrelid oid, adnum int2, adbin pg_node_tree"),
            table("pg_inherits", 2611, false, "inhrelid oid, inhparent oid, inhseqno int4, inhdetachpending bool"),
            table("pg_trigger", 2620, false, "oid oid, tgrelid oid, tgparentid oid, tgname name, tgfoid oid,"
                    + " tgtype int2, tgenabled char, tgisinternal bool, tgconstrrelid oid, tgconstrindid oid,"
                    + " tgconstraint oid, tgdeferrable bool, tginitdeferred bool, tgnargs int2, tgattr int2vector,"
                    + " tgargs bytea, tgqual pg_node_tree, tgoldtable name, tgnewtable name"),
            table("pg_rewrite", 2618, false, "oid oid, rulename name, ev_class oid, ev_type char, ev_enabled char,"
                    + " is_instead bool, ev_qual pg_node_tree, ev_action pg_node_tree"),
            table("pg_depend", 2608, false, "classid oid, objid oid, objsubid int4, refclassid oid, refobjid oid,"
                    + " refobjsubid int4, deptype char"),
            table("pg_enum", 3501, false, "oid oid, enumtypid oid, enumsortorder float4, enumlabel name"),
            table("pg_policy", 3256, false, "oid oid, polname name, polrelid oid, polcmd char, polpermissive bool,"
                    + " polroles oid[], polqual pg_node_tree, polwithcheck pg_node_tree"),
            table("pg_policies", 12_008, true, "schemaname name, tablename name, policyname name, permissive text,"
                    + " roles name[], cmd text, qual text, with_check text"),
            table("pg_statistic_ext", 3381, false, "oid oid, stxrelid oid, stxname name, stxnamespace oid,"
                    + " stxowner oid, stxkeys int2vector, stxstattarget int2, stxkind char[],"
                    + " stxexprs pg_node_tree"),
            table("pg_publication", 6104, false, "oid oid, pubname name, pubowner oid, puballtables bool,"
                    + " pubinsert bool, pubupdate bool, pubdelete bool, pubtruncate bool, pubviaroot bool,"
                    + " pubgencols char"),
            table("pg_publication_rel", 6106, false, "oid oid, prpubid oid, prrelid oid, prqual pg_node_tree,"
                    + " prattrs int2vector"),
            table("pg_publication_namespace", 6237, false, "oid oid, pnpubid oid, pnnspid oid"),
            table("pg_sequence", 2224, false, "seqrelid oid, seqtypid oid, seqstart int8, seqincrement int8,"
                    + " seqmax int8, seqmin int8, seqcache int8, seqcycle bool"),
            table("pg_extension", 3079, false, "oid oid, extname name, extowner oid, extnamespace oid,"
                    + " extrelocatable bool, extversion text, extconfig oid[], extcondition text[]"),
            table("pg_foreign_table", 3118, false, "ftrelid oid, ftserver oid, ftoptions text[]"),
            table("pg_foreign_server", 1417, false, "oid oid, srvname name, srvowner oid, srvfdw oid,"
                    + " srvtype text, srvversion text, srvacl aclitem[], srvoptions text[]"),
            table("pg_event_trigger", 3466, false, "oid oid, evtname name, evtevent name, evtowner oid,"
                    + " evtfoid oid, evtenabled char, evttags text[]"),
            table("pg_cast", 2605, false, "oid oid, castsource oid, casttarget oid, castfunc oid,"
                    + " castcontext char, castmethod char"),
            table("pg_operator", 2617, false, "oid oid, oprname name, oprnamespace oid, oprowner oid,"
                    + " oprkind char, oprcanmerge bool, oprcanhash bool, oprleft oid, oprright oid,"
                    + " oprresult oid, oprcom oid, oprnegate oid, oprcode regproc, oprrest regproc,"
                    + " oprjoin regproc"),
            table("pg_get_keywords", 12_009, true, "word text, catcode char, barelabel bool, catdesc text,"
                    + " baredesc text"));

    /** The tables of {@code information_schema}. */
    static final List<Table> INFORMATION_TABLES = List.of(
            table("schemata", 13_101, true, "catalog_name sql_identifier, schema_name sql_identifier,"
                    + " schema_owner sql_identifier, default_character_set_catalog sql_identifier,"
                    + " default_character_set_schema sql_identifier, default_character_set_name sql_identifier,"
                    + " sql_path character_data"),
            table("tables", 13_102, true, "table_catalog sql_identifier, table_schema sql_identifier,"
                    + " table_name sql_identifier, table_type character_data,"
                    + " self_referencing_column_name sql_identifier, reference_generation character_data,"
                    + " user_defined_type_catalog sql_identifier, user_defined_type_schema sql_identifier,"
                    + " user_defined_type_name sql_identifier, is_insertable_into yes_or_no, is_typed yes_or_no,"
                    + " commit_action character_data"),
            table("columns", 13_103, true, "table_catalog sql_identifier, table_schema sql_identifier,"
                    + " table_name sql_identifier, column_name sql_identifier, ordinal_position cardinal_number,"
                    + " column_default character_data, is_nullable yes_or_no, data_type character_data,"
                    + " character_maximum_length cardinal_number, character_octet_length cardinal_number,"
                    + " numeric_precision cardinal_number, numeric_precision_radix cardinal_number,"
                    + " numeric_scale cardinal_number, datetime_precision cardinal_number,"
                    + " interval_type character_data, interval_precision cardinal_number,"
                    + " character_set_catalog sql_identifier, character_set_schema sql_identifier,"
                    + " character_set_name sql_identifier, collation_catalog sql_identifier,"
                    + " collation_schema sql_identifier, collation_name sql_identifier,"
                    + " domain_catalog sql_identifier, domain_schema sql_identifier, domain_name sql_identifier,"
                    + " udt_catalog sql_identifier, udt_schema sql_identifier, udt_name sql_identifier,"
                    + " scope_catalog sql_identifier, scope_schema sql_identifier, scope_name sql_identifier,"
                    + " maximum_cardinality cardinal_number, dtd_identifier sql_identifier,"
                    + " is_self_referencing yes_or_no, is_identity yes_or_no, identity_generation character_data,"
                    + " identity_start character_data, identity_increment character_data,"
                    + " identity_maximum character_data, identity_minimum character_data, identity_cycle yes_or_no,"
                    + " is_generated character_data, generation_expression character_data, is_updatable yes_or_no"),
            table("views", 13_104, true, "table_catalog sql_identifier, table_schema sql_identifier,"
                    + " table_name sql_identifier, view_definition character_data, check_option character_data,"
                    + " is_updatable yes_or_no, is_insertable_into yes_or_no, is_trigger_updatable yes_or_no,"
                    + " is_trigger_deletable yes_or_no, is_trigger_insertable_into yes_or_no"),
            table("table_constraints", 13_105, true, "constraint_catalog sql_identifier,"
                    + " constraint_schema sql_identifier, constraint_name sql_identifier,"
                    + " table_catalog sql_identifier, table_schema sql_identifier, table_name sql_identifier,"
                    + " constraint_type character_data, is_deferrable yes_or_no, initially_deferred yes_or_no,"
                    + " enforced yes_or_no, nulls_distinct yes_or_no"),
            table("key_column_usage", 13_106, true, "constraint_catalog sql_identifier,"
                    + " constraint_schema sql_identifier, constraint_name sql_identifier,"
                    + " table_catalog sql_identifier, table_schema sql_identifier, table_name sql_identifier,"
                    + " column_name sql_identifier, ordinal_position cardinal_number,"
                    + " position_in_unique_constraint cardinal_number"),
            table("referential_constraints", 13_107, true, "constraint_catalog sql_identifier,"
                    + " constraint_schema sql_identifier, constraint_name sql_identifier,"
                    + " unique_constraint_catalog sql_identifier, unique_constraint_schema sql_identifier,"
                    + " unique_constraint_name sql_identifier, match_option character_data,"
                    + " update_rule character_data, delete_rule character_data"));

    // -------------------------------------------------------------- types

    /**
     * A type of PostgreSQL in {@code pg_type}.
     *
     * @param category the category, such as {@code N} for numbers
     */
    private record PgType(int oid, String name, int length, boolean byValue, char category, boolean preferred,
            int array, char align) {
    }

    /** The scalar types of PostgreSQL the server knows, with their OIDs. */
    private static final List<PgType> TYPES = List.of(
            new PgType(16, "bool", 1, true, 'B', true, 1000, 'c'),
            new PgType(17, "bytea", -1, false, 'U', false, 1001, 'i'),
            new PgType(18, "char", 1, true, 'Z', false, 1002, 'c'),
            new PgType(19, "name", 64, false, 'S', false, 1003, 'c'),
            new PgType(20, "int8", 8, true, 'N', false, 1016, 'd'),
            new PgType(21, "int2", 2, true, 'N', false, 1005, 's'),
            new PgType(22, "int2vector", -1, false, 'A', false, 1006, 'i'),
            new PgType(23, "int4", 4, true, 'N', false, 1007, 'i'),
            new PgType(24, "regproc", 4, true, 'N', false, 1008, 'i'),
            new PgType(25, "text", -1, false, 'S', true, 1009, 'i'),
            new PgType(26, "oid", 4, true, 'N', true, 1028, 'i'),
            new PgType(28, "xid", 4, true, 'U', false, 1011, 'i'),
            new PgType(30, "oidvector", -1, false, 'A', false, 1013, 'i'),
            new PgType(114, "json", -1, false, 'U', false, 199, 'i'),
            new PgType(194, "pg_node_tree", -1, false, 'Z', false, 0, 'i'),
            new PgType(700, "float4", 4, true, 'N', false, 1021, 'i'),
            new PgType(701, "float8", 8, true, 'N', true, 1022, 'd'),
            new PgType(705, "unknown", -2, false, 'X', false, 0, 'c'),
            new PgType(1033, "aclitem", 16, false, 'U', false, 1034, 'd'),
            new PgType(1042, "bpchar", -1, false, 'S', false, 1014, 'i'),
            new PgType(1043, "varchar", -1, false, 'S', false, 1015, 'i'),
            new PgType(1082, "date", 4, true, 'D', false, 1182, 'i'),
            new PgType(1083, "time", 8, true, 'D', false, 1183, 'd'),
            new PgType(1114, "timestamp", 8, true, 'D', false, 1115, 'd'),
            new PgType(1184, "timestamptz", 8, true, 'D', true, 1185, 'd'),
            new PgType(1186, "interval", 16, false, 'T', true, 1187, 'd'),
            new PgType(1266, "timetz", 12, false, 'D', false, 1270, 'd'),
            new PgType(1700, "numeric", -1, false, 'N', false, 1231, 'i'),
            new PgType(2205, "regclass", 4, true, 'N', false, 2210, 'i'),
            new PgType(2206, "regtype", 4, true, 'N', false, 2211, 'i'),
            new PgType(2249, "record", -1, false, 'P', false, 2287, 'd'),
            new PgType(2275, "cstring", -2, false, 'P', false, 1263, 'c'),
            new PgType(2276, "any", 4, true, 'P', false, 0, 'i'),
            new PgType(2277, "anyarray", -1, false, 'P', false, 0, 'd'),
            new PgType(2278, "void", 4, true, 'P', false, 0, 'i'),
            new PgType(2283, "anyelement", 4, true, 'P', false, 0, 'i'),
            new PgType(2950, "uuid", 16, false, 'U', false, 2951, 'c'),
            new PgType(3802, "jsonb", -1, false, 'U', false, 3807, 'i'));

    /** The name of a type of PostgreSQL by its OID, arrays as {@code _int4}, or {@code null}. */
    static String typeName(int oid) {
        for (PgType t : TYPES) {
            if (t.oid() == oid) {
                return t.name();
            }
            if (t.array() == oid) {
                return "_" + t.name();
            }
        }
        return null;
    }

    /** The OID of a type of PostgreSQL by its name, {@code int4[]} or {@code _int4} for an array, or 0. */
    static int typeOid(String name) {
        String n = name.toLowerCase(Locale.ROOT).strip();
        boolean array = n.endsWith("[]") || n.startsWith("_");
        String element = n.endsWith("[]") ? n.substring(0, n.length() - 2) : n.startsWith("_") ? n.substring(1) : n;
        element = switch (element) {
            case "integer", "int" -> "int4";
            case "smallint" -> "int2";
            case "bigint" -> "int8";
            case "boolean" -> "bool";
            case "real" -> "float4";
            case "double precision" -> "float8";
            case "character varying" -> "varchar";
            case "character" -> "bpchar";
            case "decimal" -> "numeric";
            case "timestamp without time zone" -> "timestamp";
            case "timestamp with time zone" -> "timestamptz";
            case "time without time zone" -> "time";
            default -> element;
        };
        for (PgType t : TYPES) {
            if (t.name().equals(element)) {
                return array ? t.array() : t.oid();
            }
        }
        return 0;
    }

    /**
     * The text {@code format_type} gives a type of PostgreSQL with a modifier,
     * such as {@code integer}, {@code numeric(10,2)} or {@code text[]}.
     */
    static String formatType(int oid, Integer modifier) {
        String name = typeName(oid);
        if (name == null) {
            return "???";
        }
        boolean array = name.startsWith("_");
        String element = array ? name.substring(1) : name;
        String text = switch (element) {
            case "int4" -> "integer";
            case "int2" -> "smallint";
            case "int8" -> "bigint";
            case "bool" -> "boolean";
            case "float4" -> "real";
            case "float8" -> "double precision";
            case "varchar" -> "character varying";
            case "bpchar" -> "character";
            case "timestamp" -> "timestamp without time zone";
            case "timestamptz" -> "timestamp with time zone";
            case "time" -> "time without time zone";
            case "char" -> "\"char\"";
            default -> element;
        };
        if (element.equals("numeric") && modifier != null && modifier >= 4) {
            int m = modifier - 4;
            text = "numeric(" + (m >> 16) + "," + (m & 0xFFFF) + ")";
        }
        return array ? text + "[]" : text;
    }

    // ------------------------------------------------------------- building

    private Snapshot build(String database, long now) {
        Rows pg = new Rows();
        Rows info = new Rows();
        List<String> schemas = database == null ? List.of() : engine.connector(database).catalog().schemas();
        Map<String, Integer> namespaces = new LinkedHashMap<>();
        namespaces.put("pg_catalog", PG_CATALOG_NAMESPACE);
        namespaces.put("information_schema", INFORMATION_SCHEMA_NAMESPACE);
        Set<Integer> taken = new HashSet<>(List.of(PG_CATALOG_NAMESPACE, INFORMATION_SCHEMA_NAMESPACE,
                PUBLIC_NAMESPACE));
        List<String> others = new ArrayList<>();
        // A schema of the source with the name of a catalog of the server stays hidden behind the catalog.
        schemas = schemas.stream().filter(s -> !s.equalsIgnoreCase("pg_catalog")
                && !s.equalsIgnoreCase("information_schema")).toList();
        for (String s : schemas) {
            if (s.equals("public")) {
                namespaces.put(s, PUBLIC_NAMESPACE);
            } else {
                others.add(s);
            }
        }
        Map<String, Integer> schemaOids = oids(others.stream().map(s -> "namespace " + database + "." + s).toList(),
                taken);
        for (String s : others) {
            namespaces.put(s, schemaOids.get("namespace " + database + "." + s));
        }
        List<String> catalogName = List.of(database == null ? "" : database);

        // The views and tables of the source and their OIDs; a view hides a table of its name.
        Views views = engine.catalog().views();
        Map<String, List<String>> tables = new LinkedHashMap<>();
        List<String> relationNames = new ArrayList<>();
        for (String s : schemas) {
            List<String> names = new ArrayList<>(views.names(database, s));
            for (String t : engine.connector(database).catalog().tables(s)) {
                if (!names.contains(t)) {
                    names.add(t);
                }
            }
            tables.put(s, names);
            for (String t : names) {
                relationNames.add("relation " + database + "." + s + "." + t);
            }
        }
        // The primary keys of the tables, each an index and a constraint with OIDs of their own.
        Map<String, List<String>> keys = new HashMap<>();
        List<String> keyNames = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : tables.entrySet()) {
            for (String t : e.getValue()) {
                TableRef ref = new TableRef(database, e.getKey(), t);
                List<String> key;
                try {
                    key = views.get(ref).isPresent() ? List.of() : engine.connector(database).catalog().primaryKey(ref);
                } catch (RuntimeException ex) {
                    key = List.of();
                }
                if (!key.isEmpty()) {
                    String name = database + "." + e.getKey() + "." + t;
                    keys.put(name, key);
                    keyNames.add("index " + name);
                    keyNames.add("constraint " + name);
                }
            }
        }
        for (Table t : PG_TABLES) {
            taken.add(t.oid());
        }
        for (Table t : INFORMATION_TABLES) {
            taken.add(t.oid());
        }
        Map<String, Integer> relationOids = oids(relationNames, taken);
        Map<String, Integer> keyOids = oids(keyNames, taken);
        Map<Integer, String> constraintDefinitions = new HashMap<>();
        Map<Integer, List<String>> indexColumns = new HashMap<>();
        Map<Integer, String> indexDefinitions = new HashMap<>();
        Map<String, Integer> classes = new HashMap<>();
        Map<Integer, String> classNames = new HashMap<>();
        Map<Integer, List<String>> columnNames = new HashMap<>();
        Map<Integer, String> viewTexts = new HashMap<>();

        for (Map.Entry<String, Integer> n : namespaces.entrySet()) {
            pg.add("pg_namespace", n.getValue(), n.getKey(), OWNER, null);
            info.add("schemata", catalogName.get(0), n.getKey(), "fyrql", null, null, null, null);
        }
        // The catalogs themselves.
        for (Map.Entry<String, List<Table>> e : Map.of("pg_catalog", PG_TABLES, "information_schema",
                INFORMATION_TABLES).entrySet()) {
            int namespace = namespaces.get(e.getKey());
            for (Table t : e.getValue()) {
                relation(pg, t.oid(), t.name(), namespace, t.view() ? "v" : "r", t.schema().size(), -1);
                attributes(pg, t.oid(), t.schema());
                classes.put(e.getKey() + "." + t.name(), t.oid());
                classNames.put(t.oid(), quoteIdentifier(e.getKey()) + "." + quoteIdentifier(t.name()));
            }
        }
        for (Map.Entry<String, List<String>> e : tables.entrySet()) {
            String schema = e.getKey();
            int namespace = namespaces.get(schema);
            for (String table : e.getValue()) {
                int oid = relationOids.get("relation " + database + "." + schema + "." + table);
                TableRef ref = new TableRef(database, schema, table);
                Optional<ViewDefinition> view = views.get(ref);
                Schema columns;
                try {
                    columns = view.isPresent() ? view.get().schema() : engine.connector(database).catalog().schema(ref);
                } catch (RuntimeException ex) {
                    // A table whose columns the source cannot tell is left out.
                    continue;
                }
                List<String> key = keys.get(database + "." + schema + "." + table);
                relation(pg, oid, table, namespace, view.isPresent() ? "v" : "r", columns.size(), -1, key != null);
                attributes(pg, oid, columns);
                if (key != null) {
                    String name = database + "." + schema + "." + table;
                    int index = keyOids.get("index " + name);
                    int constraint = keyOids.get("constraint " + name);
                    String keyName = table + "_pkey";
                    List<Short> attnums = new ArrayList<>();
                    List<Schema.Field> keyFields = new ArrayList<>();
                    List<String> quoted = new ArrayList<>();
                    for (String k : key) {
                        attnums.add((short) (columns.indexOf(k) + 1));
                        keyFields.add(columns.fields().get(columns.indexOf(k)));
                        quoted.add(quoteIdentifier(k));
                    }
                    List<Integer> zeros = attnums.stream().map(a -> 0).toList();
                    relation(pg, index, keyName, namespace, "i", key.size(), -1, false);
                    attributes(pg, index, new Schema(keyFields));
                    pg.add("pg_index", index, oid, (short) key.size(), (short) key.size(), true, false, true, false,
                            true, false, true, false, true, true, false, attnums, zeros, zeros,
                            attnums.stream().map(a -> (short) 0).toList(), null, null);
                    pg.add("pg_constraint", constraint, keyName, namespace, "p", false, false, true, true, oid, 0,
                            index, 0, 0, " ", " ", " ", true, (short) 0, true, false, attnums, null, null, null, null,
                            null, null, null);
                    info.add("table_constraints", database, schema, keyName, database, schema, table, "PRIMARY KEY",
                            "NO", "NO", "YES", null);
                    for (int k = 0; k < key.size(); k++) {
                        info.add("key_column_usage", database, schema, keyName, database, schema, table, key.get(k),
                                k + 1, null);
                    }
                    classes.put(schema.toLowerCase(Locale.ROOT) + "." + keyName.toLowerCase(Locale.ROOT), index);
                    classNames.put(index, quoteIdentifier(schema) + "." + quoteIdentifier(keyName));
                    constraintDefinitions.put(constraint, "PRIMARY KEY (" + String.join(", ", quoted) + ")");
                    indexColumns.put(index, key);
                    indexDefinitions.put(index, "CREATE UNIQUE INDEX " + quoteIdentifier(keyName) + " ON "
                            + quoteIdentifier(schema) + "." + quoteIdentifier(table) + " USING btree ("
                            + String.join(", ", quoted) + ")");
                }
                columnNames.put(oid, columns.fields().stream().map(Schema.Field::name).toList());
                classes.put(schema.toLowerCase(Locale.ROOT) + "." + table.toLowerCase(Locale.ROOT), oid);
                classNames.put(oid, quoteIdentifier(schema) + "." + quoteIdentifier(table));
                if (view.isPresent()) {
                    String text = view.get().text();
                    viewTexts.put(oid, text);
                    pg.add("pg_views", schema, table, "fyrql", text);
                    info.add("tables", database, schema, table, "VIEW", null, null, null, null, null, "NO", "NO",
                            null);
                    info.add("views", database, schema, table, text, "NONE", "NO", "NO", "NO", "NO", "NO");
                } else {
                    pg.add("pg_tables", schema, table, "fyrql", null, false, false, false, false);
                    pg.add("pg_stat_user_tables", oid, schema, table, 0L, null, 0L, null, null, null, 0L, 0L, 0L, 0L,
                            0L, null, 0L, 0L, 0L, null, null, null, null, 0L, 0L, 0L, 0L);
                    info.add("tables", database, schema, table, "BASE TABLE", null, null, null, null, null, "YES",
                            "NO", null);
                }
                for (int i = 0; i < columns.size(); i++) {
                    Schema.Field f = columns.fields().get(i);
                    info.add("columns", columnRow(database, schema, table, i + 1, f));
                }
            }
        }
        for (PgType t : TYPES) {
            type(pg, t.oid(), t.name(), t.length(), t.byValue(), t.category(), t.preferred(), 0, t.array(),
                    t.name() + "in", t.name() + "out", t.align());
            if (t.array() != 0) {
                type(pg, t.array(), "_" + t.name(), -1, false, 'A', false, t.oid(), 0, "array_in", "array_out", 'i');
            }
        }
        for (String source : engine.sources()) {
            pg.add("pg_database", databaseOid(source), source, OWNER, 6, "c", false, true, false, -1, 0, 0, 1663,
                    "C", "C", null, null, null, null);
        }
        pg.add("pg_roles", "fyrql", false, true, false, false, true, false, -1, "********", null, false, null,
                OWNER);
        pg.add("pg_user", "fyrql", OWNER, false, false, false, false, "********", null, null);
        pg.add("pg_am", 2, "heap", "heap_tableam_handler", "t");
        pg.add("pg_am", 403, "btree", "bthandler", "i");
        pg.add("pg_tablespace", 1663, "pg_default", OWNER, null, null);
        pg.add("pg_language", 12, "internal", OWNER, false, false, 0, 0, 0, null);
        pg.add("pg_language", 14, "sql", OWNER, false, true, 0, 0, 0, null);
        pg.add("pg_collation", 100, "default", PG_CATALOG_NAMESPACE, OWNER, "d", true, -1, null, null, null, null,
                null);
        pg.add("pg_collation", 950, "C", PG_CATALOG_NAMESPACE, OWNER, "c", true, -1, "C", "C", null, null, null);
        int procOid = 1;
        for (String f : functions) {
            pg.add("pg_proc", FIRST_OID - 1 - procOid++ % (FIRST_OID - 1), f, PG_CATALOG_NAMESPACE, OWNER, 12, 1f,
                    0f, 0, "-", "f", false, false, false, false, "i", "s", (short) 0, (short) 0, 25, List.of(), null,
                    null, null, null, null, f, null, null, null, null);
        }
        for (Map.Entry<String, String> s : defaults.all().entrySet()) {
            String value = s.getValue();
            pg.add("pg_settings", s.getKey(), value, null, "FyrQL", null, null, "user", "string", "default", null,
                    null, null, value, value, null, null, false);
        }
        for (String keyword : KEYWORDS) {
            pg.add("pg_get_keywords", keyword, "R", false, "reserved", "requires AS");
        }

        MemoryConnector.Builder pgBuilder = MemoryConnector.builder("pg_catalog");
        for (Table t : PG_TABLES) {
            pgBuilder.table(t.name(), t.schema(), pg.rows(t.name()));
        }
        MemoryConnector.Builder infoBuilder = MemoryConnector.builder("information_schema");
        for (Table t : INFORMATION_TABLES) {
            infoBuilder.table(t.name(), t.schema(), info.rows(t.name()));
        }
        Map<String, Integer> namespaceOids = new LinkedHashMap<>(namespaces);
        return new Snapshot(now, pgBuilder.build(), infoBuilder.build(), Map.copyOf(classes),
                Map.copyOf(namespaceOids), Map.copyOf(classNames), Map.copyOf(columnNames), Map.copyOf(viewTexts),
                Map.copyOf(constraintDefinitions), Map.copyOf(indexColumns), Map.copyOf(indexDefinitions));
    }

    /** The rows of the tables of a catalog, by table. */
    private static final class Rows {
        private final Map<String, List<Object[]>> rows = new HashMap<>();

        void add(String table, Object... values) {
            rows.computeIfAbsent(table, k -> new ArrayList<>()).add(values);
        }

        List<Object[]> rows(String table) {
            return rows.getOrDefault(table, List.of());
        }
    }

    private static void relation(Rows pg, int oid, String name, int namespace, String kind, int columns,
            float tuples) {
        relation(pg, oid, name, namespace, kind, columns, tuples, false);
    }

    /**
     * Adds a relation to {@code pg_class}: a table, a view or an index.
     *
     * @param indexed whether a table has an index, as one with a primary key has
     */
    private static void relation(Rows pg, int oid, String name, int namespace, String kind, int columns,
            float tuples, boolean indexed) {
        pg.add("pg_class", oid, name, namespace, 0, 0, OWNER, kind.equals("r") ? 2 : kind.equals("i") ? 403 : 0, oid,
                0, 0, tuples, 0, 0, 0, indexed, false, "p", kind, (short) columns, (short) 0, false, false, false,
                false, false, true, "d", false, 0, 0, 0, null, null, null);
    }

    private static void attributes(Rows pg, int relation, Schema schema) {
        for (int i = 0; i < schema.size(); i++) {
            Schema.Field f = schema.fields().get(i);
            PgTypes.PgType t = PgTypes.of(f.type());
            int dimensions = f.type() instanceof DataType.ListType ? 1 : 0;
            pg.add("pg_attribute", relation, f.name(), t.oid(), t.length(), (short) (i + 1), t.modifier(),
                    (short) dimensions, t.length() > 0 && t.length() <= 8, "i", t.length() < 0 ? "x" : "p", "",
                    !f.nullable(), false, false, "", "", false, true, (short) 0, 0, null, null, null, null, null);
        }
    }

    private static void type(Rows pg, int oid, String name, int length, boolean byValue, char category,
            boolean preferred, int element, int array, String input, String output, char align) {
        pg.add("pg_type", oid, name, PG_CATALOG_NAMESPACE, OWNER, (short) length, byValue,
                category == 'P' ? "p" : "b", String.valueOf(category), preferred, true, ",", 0,
                category == 'A' ? "array_subscript_handler" : "-", element, array, input, output,
                name + "recv", name + "send", "-", "-", "-", String.valueOf(align), length < 0 ? "x" : "p", false, 0,
                -1, 0, name.equals("text") || name.equals("varchar") || name.equals("bpchar") || name.equals("name")
                        ? 100 : 0, null, null, null);
    }

    private static Object[] columnRow(String database, String schema, String table, int ordinal, Schema.Field f) {
        DataType type = f.type();
        String dataType = dataType(type);
        Integer precision = null;
        Integer radix = null;
        Integer scale = null;
        if (type instanceof DataType.Decimal d) {
            precision = d.precision();
            radix = 10;
            scale = d.scale();
        } else if (type.isIntegral() && type != DataType.HUGEINT) {
            precision = type == DataType.BIGINT ? 64 : type == DataType.INTEGER ? 32 : 16;
            radix = 2;
            scale = 0;
        } else if (type.isFloatingPoint()) {
            precision = type == DataType.FLOAT ? 24 : 53;
            radix = 2;
        }
        String udt = PgCatalog.typeName(PgTypes.oid(type));
        return new Object[] {database, schema, table, f.name(), ordinal, null, f.nullable() ? "YES" : "NO", dataType,
                null, null, precision, radix, scale, null, null, null, null, null, null, null, null, null, null, null,
                null, database, "pg_catalog", udt, null, null, null, null, Integer.toString(ordinal), "NO", "NO", null,
                null, null, null, null, "NO", "NEVER", null, "YES"};
    }

    /** The {@code data_type} of {@code information_schema.columns}. */
    private static String dataType(DataType type) {
        if (type instanceof DataType.ListType l && !l.element().isNested()) {
            return "ARRAY";
        }
        return formatType(PgTypes.oid(type), null);
    }

    /** Quotes a name where PostgreSQL would, as {@code ::regclass} writes it. */
    static String quoteIdentifier(String name) {
        boolean plain = !name.isEmpty() && (Character.isLowerCase(name.charAt(0)) || name.charAt(0) == '_');
        for (int i = 0; plain && i < name.length(); i++) {
            char c = name.charAt(i);
            plain = Character.isLowerCase(c) || Character.isDigit(c) || c == '_';
        }
        return plain ? name : "\"" + name.replace("\"", "\"\"") + "\"";
    }

    /** The reserved words of PostgreSQL, as {@code pg_get_keywords} lists them. */
    static final List<String> KEYWORDS = Arrays.asList(("all analyse analyze and any array as asc asymmetric both"
            + " case cast check collate column constraint create current_catalog current_date current_role"
            + " current_time current_timestamp current_user default deferrable desc distinct do else end except false"
            + " fetch for foreign from grant group having in initially intersect into lateral leading limit"
            + " localtime localtimestamp not null offset on only or order placing primary references returning"
            + " select session_user some symmetric system_user table then to trailing true union unique user using"
            + " variadic when where window with").split(" "));
}
