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

/**
 * The interfaces a source of tables implements. A {@link
 * net.fyrql.connector.Connector} answers for its catalog, negotiates what
 * it takes over of a scan, splits the scan into parts and reads each part
 * as batches; a {@link net.fyrql.connector.ConnectorFactory} creates one
 * from configuration.
 * <p>
 * The conventions of the negotiation, which every connector and the engine
 * keep:
 * <ul>
 * <li>A {@link net.fyrql.connector.ScanRequest} names the columns to read
 * as {@link net.fyrql.connector.ColumnPath}s. Its filters, its order and
 * its aggregation refer to positions in that column list, the output of
 * the scan, never to the table schema.</li>
 * <li>A path may name a field inside a {@code STRUCT} column, which the
 * connector then delivers as a column of its own, so that it need not read
 * the rest of the struct. A connector that reads whole columns only
 * answers with the whole columns of
 * {@link net.fyrql.connector.ScanRequest#withWholeColumns()} and takes
 * over nothing else; the engine then takes the fields out itself. The
 * engine asks for fields first with the columns alone, and names them
 * again later only if the connector read them.</li>
 * <li>The filters are conjuncts of type {@code BOOLEAN}; a connector may
 * accept any subset. The order and the aggregation are accepted as a
 * whole or not at all.</li>
 * <li>A connector may accept a limit only when it accepts every filter and
 * the order, if one is requested; it then delivers at most that many rows.
 * An aggregation is requested only without limit and order, and a
 * connector may accept it only when it accepts every filter.</li>
 * <li>Whatever a connector accepts, it evaluates with exactly the semantics
 * of the engine. A filter the source would evaluate differently, e.g.
 * under a collation that ignores case, stays with the engine. The engine
 * runs the same query with and without pushdown in its tests to check
 * this.</li>
 * <li>The engine asks {@link net.fyrql.connector.Connector#plan} when it
 * plans a query and again when it opens the scan; both answers must be the
 * same. The output schema of the plan must have the types the engine
 * expects: the requested columns, or the groups and aggregates of an
 * accepted aggregation.</li>
 * <li>{@link net.fyrql.connector.Capabilities#plan} applies these rules for
 * a connector that describes what it supports with predicates.</li>
 * <li>Besides scans, the engine offers a source the parts of a plan that
 * join tables of that source only, through
 * {@link net.fyrql.connector.Connector#planRemote}, largest part first. A
 * source accepts a part as a whole or not at all, with the same semantics
 * as the engine, and with an output of the types of the part. The engine
 * asks when it plans a query and again when it opens the part.</li>
 * </ul>
 * The conventions of writing:
 * <ul>
 * <li>A source that holds tables that can be written offers a
 * {@link net.fyrql.connector.Writer} through
 * {@link net.fyrql.connector.Connector#writer()}; a source that writes
 * files offers formats as a {@link net.fyrql.connector.FileWriterProvider}.
 * Both write through a {@link net.fyrql.connector.Sink}. A source that reads
 * files with columns given in advance, for {@code COPY ... FROM}, offers
 * them as a {@link net.fyrql.connector.FileReaderProvider}.</li>
 * <li>Files are named by locations as text: a local path or a location
 * with a scheme, such as {@code s3://bucket/key}. A source that holds the
 * files of a scheme offers a {@link net.fyrql.connector.FileStore} through
 * {@link net.fyrql.connector.Connector#fileStore()}; the engine gives all
 * stores to every source as {@link net.fyrql.connector.FileStores} through
 * {@link net.fyrql.connector.Connector#fileStores}, once all sources are
 * created, and the sources of file formats read and write through
 * them.</li>
 * <li>The engine writes the batches of a sink in the schema it opened the
 * sink with, from one thread, and then commits it or closes it without a
 * commit, which abandons the write.</li>
 * <li>A source changes rows in one of two ways. It takes a whole
 * {@code UPDATE} or {@code DELETE} over with
 * {@link net.fyrql.connector.Writer#update} or
 * {@link net.fyrql.connector.Writer#delete}, evaluating the expressions with
 * exactly the semantics of the engine, like the filters of a scan, or it
 * declines with an empty answer. Otherwise, and for statements that read
 * other tables, such as {@code MERGE}, the engine reads the rows to change
 * first, completely, and then hands them to
 * {@link net.fyrql.connector.RowChanges} by key: the primary key of the
 * table, or all of its columns if it has none.</li>
 * <li>A source that keeps a primary key reports it through
 * {@link net.fyrql.connector.Catalog#primaryKey} and checks it with every
 * write, so that clients can edit rows by it.</li>
 * <li>Each statement is a transaction of its own in one source; there is
 * no transaction over several statements or sources.</li>
 * </ul>
 * The rules by which the SPI evolves within a major version, so that
 * connectors written against one minor version keep working with the
 * next:
 * <ul>
 * <li>Interfaces of the SPI get new methods only with defaults.</li>
 * <li>{@link net.fyrql.core.plan.PlanNode} and
 * {@link net.fyrql.core.expr.Expression} may get new kinds of nodes.
 * The engine offers a connector filters and parts of plans with any
 * kind, whatever its {@link net.fyrql.connector.Capabilities} say; a
 * connector declines what it does not know, e.g. with a {@code default}
 * branch that takes the filter or the part over not at all.</li>
 * <li>The components of the records of the SPI stay the same.</li>
 * </ul>
 * <p>
 * Part of the SPI for connectors; covered by the compatibility promise.
 */
package net.fyrql.connector;
