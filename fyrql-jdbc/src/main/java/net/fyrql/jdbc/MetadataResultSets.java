/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fyrql.jdbc;

import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * The columns of the result sets of {@link java.sql.DatabaseMetaData}, in
 * the order and with the names the JDBC specification gives. A column is
 * text unless its layout names a type: {@code int} is {@code INTEGER},
 * {@code short} {@code SMALLINT}, {@code long} {@code BIGINT} and
 * {@code boolean} {@code BOOLEAN}, so that the getters of these Java types
 * read them.
 */
final class MetadataResultSets {

    static final Schema CATALOGS = layout("TABLE_CAT");
    static final Schema SCHEMAS = layout("TABLE_SCHEM TABLE_CATALOG");
    static final Schema TABLE_TYPES = layout("TABLE_TYPE");
    static final Schema TABLES = layout("TABLE_CAT TABLE_SCHEM TABLE_NAME TABLE_TYPE REMARKS TYPE_CAT TYPE_SCHEM"
            + " TYPE_NAME SELF_REFERENCING_COL_NAME REF_GENERATION");
    static final Schema COLUMNS = layout("TABLE_CAT TABLE_SCHEM TABLE_NAME COLUMN_NAME DATA_TYPE:int TYPE_NAME"
            + " COLUMN_SIZE:int BUFFER_LENGTH:int DECIMAL_DIGITS:int NUM_PREC_RADIX:int NULLABLE:int REMARKS"
            + " COLUMN_DEF SQL_DATA_TYPE:int SQL_DATETIME_SUB:int CHAR_OCTET_LENGTH:int ORDINAL_POSITION:int"
            + " IS_NULLABLE SCOPE_CATALOG SCOPE_SCHEMA SCOPE_TABLE SOURCE_DATA_TYPE:short IS_AUTOINCREMENT"
            + " IS_GENERATEDCOLUMN");
    static final Schema COLUMN_PRIVILEGES = layout("TABLE_CAT TABLE_SCHEM TABLE_NAME COLUMN_NAME GRANTOR GRANTEE"
            + " PRIVILEGE IS_GRANTABLE");
    static final Schema TABLE_PRIVILEGES = layout("TABLE_CAT TABLE_SCHEM TABLE_NAME GRANTOR GRANTEE PRIVILEGE"
            + " IS_GRANTABLE");
    static final Schema BEST_ROW_IDENTIFIER = layout("SCOPE:short COLUMN_NAME DATA_TYPE:int TYPE_NAME"
            + " COLUMN_SIZE:int BUFFER_LENGTH:int DECIMAL_DIGITS:short PSEUDO_COLUMN:short");
    static final Schema VERSION_COLUMNS = layout("SCOPE:short COLUMN_NAME DATA_TYPE:int TYPE_NAME COLUMN_SIZE:int"
            + " BUFFER_LENGTH:int DECIMAL_DIGITS:short PSEUDO_COLUMN:short");
    static final Schema PRIMARY_KEYS = layout("TABLE_CAT TABLE_SCHEM TABLE_NAME COLUMN_NAME KEY_SEQ:short PK_NAME");
    /** The layout of the imported and exported keys and of the cross references. */
    static final Schema KEYS = layout("PKTABLE_CAT PKTABLE_SCHEM PKTABLE_NAME PKCOLUMN_NAME FKTABLE_CAT"
            + " FKTABLE_SCHEM FKTABLE_NAME FKCOLUMN_NAME KEY_SEQ:short UPDATE_RULE:short DELETE_RULE:short FK_NAME"
            + " PK_NAME DEFERRABILITY:short");
    static final Schema TYPE_INFO = layout("TYPE_NAME DATA_TYPE:int PRECISION:int LITERAL_PREFIX LITERAL_SUFFIX"
            + " CREATE_PARAMS NULLABLE:short CASE_SENSITIVE:boolean SEARCHABLE:short UNSIGNED_ATTRIBUTE:boolean"
            + " FIXED_PREC_SCALE:boolean AUTO_INCREMENT:boolean LOCAL_TYPE_NAME MINIMUM_SCALE:short"
            + " MAXIMUM_SCALE:short SQL_DATA_TYPE:int SQL_DATETIME_SUB:int NUM_PREC_RADIX:int");
    static final Schema INDEX_INFO = layout("TABLE_CAT TABLE_SCHEM TABLE_NAME NON_UNIQUE:boolean INDEX_QUALIFIER"
            + " INDEX_NAME TYPE:short ORDINAL_POSITION:short COLUMN_NAME ASC_OR_DESC CARDINALITY:long PAGES:long"
            + " FILTER_CONDITION");
    static final Schema UDTS = layout("TYPE_CAT TYPE_SCHEM TYPE_NAME CLASS_NAME DATA_TYPE:int REMARKS"
            + " BASE_TYPE:short");
    static final Schema SUPER_TYPES = layout("TYPE_CAT TYPE_SCHEM TYPE_NAME SUPERTYPE_CAT SUPERTYPE_SCHEM"
            + " SUPERTYPE_NAME");
    static final Schema SUPER_TABLES = layout("TABLE_CAT TABLE_SCHEM TABLE_NAME SUPERTABLE_NAME");
    static final Schema ATTRIBUTES = layout("TYPE_CAT TYPE_SCHEM TYPE_NAME ATTR_NAME DATA_TYPE:int ATTR_TYPE_NAME"
            + " ATTR_SIZE:int DECIMAL_DIGITS:int NUM_PREC_RADIX:int NULLABLE:int REMARKS ATTR_DEF SQL_DATA_TYPE:int"
            + " SQL_DATETIME_SUB:int CHAR_OCTET_LENGTH:int ORDINAL_POSITION:int IS_NULLABLE SCOPE_CATALOG"
            + " SCOPE_SCHEMA SCOPE_TABLE SOURCE_DATA_TYPE:short");
    static final Schema PROCEDURES = layout("PROCEDURE_CAT PROCEDURE_SCHEM PROCEDURE_NAME RESERVED1 RESERVED2"
            + " RESERVED3 REMARKS PROCEDURE_TYPE:short SPECIFIC_NAME");
    static final Schema PROCEDURE_COLUMNS = layout("PROCEDURE_CAT PROCEDURE_SCHEM PROCEDURE_NAME COLUMN_NAME"
            + " COLUMN_TYPE:short DATA_TYPE:int TYPE_NAME PRECISION:int LENGTH:int SCALE:short RADIX:short"
            + " NULLABLE:short REMARKS COLUMN_DEF SQL_DATA_TYPE:int SQL_DATETIME_SUB:int CHAR_OCTET_LENGTH:int"
            + " ORDINAL_POSITION:int IS_NULLABLE SPECIFIC_NAME");
    static final Schema CLIENT_INFO_PROPERTIES = layout("NAME MAX_LEN:int DEFAULT_VALUE DESCRIPTION");
    static final Schema FUNCTIONS = layout("FUNCTION_CAT FUNCTION_SCHEM FUNCTION_NAME REMARKS FUNCTION_TYPE:short"
            + " SPECIFIC_NAME");
    static final Schema FUNCTION_COLUMNS = layout("FUNCTION_CAT FUNCTION_SCHEM FUNCTION_NAME COLUMN_NAME"
            + " COLUMN_TYPE:short DATA_TYPE:int TYPE_NAME PRECISION:int LENGTH:int SCALE:short RADIX:short"
            + " NULLABLE:short REMARKS CHAR_OCTET_LENGTH:int ORDINAL_POSITION:int IS_NULLABLE SPECIFIC_NAME");
    static final Schema PSEUDO_COLUMNS = layout("TABLE_CAT TABLE_SCHEM TABLE_NAME COLUMN_NAME DATA_TYPE:int"
            + " COLUMN_SIZE:int DECIMAL_DIGITS:int NUM_PREC_RADIX:int COLUMN_USAGE REMARKS CHAR_OCTET_LENGTH:int"
            + " IS_NULLABLE");

    private MetadataResultSets() {
    }

    /** Returns the columns of a layout: names separated by spaces, each with an optional {@code :type}. */
    static Schema layout(String columns) {
        List<Schema.Field> fields = new ArrayList<>();
        for (String column : columns.split(" ")) {
            int colon = column.indexOf(':');
            String name = colon < 0 ? column : column.substring(0, colon);
            DataType type = colon < 0 ? DataType.VARCHAR : switch (column.substring(colon + 1)) {
                case "int" -> DataType.INTEGER;
                case "short" -> DataType.SMALLINT;
                case "long" -> DataType.BIGINT;
                case "boolean" -> DataType.BOOLEAN;
                default -> throw new IllegalArgumentException("Unknown type of the column " + column);
            };
            fields.add(new Schema.Field(name, type));
        }
        return new Schema(fields);
    }

    /** Returns a result set of rows, whose values have the Java types of the layout. */
    static ResultSet of(Schema layout, List<Object[]> rows) {
        return new FyrQLResultSet(null, BatchSource.of(layout, rows), 0, null, ResultSet.CLOSE_CURSORS_AT_COMMIT);
    }

    /** Returns a result set without rows. */
    static ResultSet empty(Schema layout) {
        return of(layout, List.of());
    }
}
