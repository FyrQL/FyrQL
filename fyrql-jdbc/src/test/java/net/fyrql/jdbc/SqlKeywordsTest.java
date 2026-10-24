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

package net.fyrql.jdbc;

import net.fyrql.sql.SqlException;
import net.fyrql.sql.parse.SqlParser;
import net.sf.jsqlparser.parser.CCJSqlParserConstants;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The keywords that the driver reports are the words that the parser does not take as names, beyond those that
 * SQL:2003 reserves. Tools such as DBeaver put them in quotes when they are names, as a source called {@code csv}.
 * The test finds them among the tokens of the parser, so that a new version of it cannot change them unnoticed.
 */
class SqlKeywordsTest {

    /** The reserved words of SQL:2003, which {@link java.sql.DatabaseMetaData#getSQLKeywords()} leaves out. */
    private static final Set<String> SQL_2003 = Set.of(("ABS ALL ALLOCATE ALTER AND ANY ARE ARRAY AS ASENSITIVE "
            + "ASYMMETRIC AT ATOMIC AUTHORIZATION AVG BEGIN BETWEEN BIGINT BINARY BLOB BOOLEAN BOTH BY CALL CALLED "
            + "CARDINALITY CASCADED CASE CAST CEIL CEILING CHAR CHAR_LENGTH CHARACTER CHARACTER_LENGTH CHECK CLOB "
            + "CLOSE COALESCE COLLATE COLLECT COLUMN COMMIT CONDITION CONNECT CONSTRAINT CONVERT CORR "
            + "CORRESPONDING COUNT COVAR_POP COVAR_SAMP CREATE CROSS CUBE CUME_DIST CURRENT CURRENT_DATE "
            + "CURRENT_DEFAULT_TRANSFORM_GROUP CURRENT_PATH CURRENT_ROLE CURRENT_TIME CURRENT_TIMESTAMP "
            + "CURRENT_TRANSFORM_GROUP_FOR_TYPE CURRENT_USER CURSOR CYCLE DATE DAY DEALLOCATE DEC DECIMAL DECLARE "
            + "DEFAULT DELETE DENSE_RANK DEREF DESCRIBE DETERMINISTIC DISCONNECT DISTINCT DOUBLE DROP DYNAMIC EACH "
            + "ELEMENT ELSE END END-EXEC ESCAPE EVERY EXCEPT EXEC EXECUTE EXISTS EXP EXTERNAL EXTRACT FALSE FETCH "
            + "FILTER FLOAT FLOOR FOR FOREIGN FREE FROM FULL FUNCTION FUSION GET GLOBAL GRANT GROUP GROUPING HAVING "
            + "HOLD HOUR IDENTITY IN INDICATOR INNER INOUT INSENSITIVE INSERT INT INTEGER INTERSECT INTERSECTION "
            + "INTERVAL INTO IS JOIN LANGUAGE LARGE LATERAL LEADING LEFT LIKE LN LOCAL LOCALTIME LOCALTIMESTAMP "
            + "LOWER MATCH MAX MEMBER MERGE METHOD MIN MINUTE MOD MODIFIES MODULE MONTH MULTISET NATIONAL NATURAL "
            + "NCHAR NCLOB NEW NO NONE NORMALIZE NOT NULL NULLIF NUMERIC OCTET_LENGTH OF OLD ON ONLY OPEN OR ORDER "
            + "OUT OUTER OVER OVERLAPS OVERLAY PARAMETER PARTITION PERCENT_RANK PERCENTILE_CONT PERCENTILE_DISC "
            + "POSITION POWER PRECISION PREPARE PRIMARY PROCEDURE RANGE RANK READS REAL RECURSIVE REF REFERENCES "
            + "REFERENCING REGR_AVGX REGR_AVGY REGR_COUNT REGR_INTERCEPT REGR_R2 REGR_SLOPE REGR_SXX REGR_SXY "
            + "REGR_SYY RELEASE RESULT RETURN RETURNS REVOKE RIGHT ROLLBACK ROLLUP ROW ROW_NUMBER ROWS SAVEPOINT "
            + "SCOPE SCROLL SEARCH SECOND SELECT SENSITIVE SESSION_USER SET SIMILAR SMALLINT SOME SPECIFIC "
            + "SPECIFICTYPE SQL SQLEXCEPTION SQLSTATE SQLWARNING SQRT START STATIC STDDEV_POP STDDEV_SAMP "
            + "SUBMULTISET SUBSTRING SUM SYMMETRIC SYSTEM SYSTEM_USER TABLE TABLESAMPLE THEN TIME TIMESTAMP "
            + "TIMEZONE_HOUR TIMEZONE_MINUTE TO TRAILING TRANSLATE TRANSLATION TREAT TRIGGER TRIM TRUE UESCAPE UNION "
            + "UNIQUE UNKNOWN UNNEST UPDATE UPPER USER USING VALUE VALUES VAR_POP VAR_SAMP VARCHAR VARYING WHEN "
            + "WHENEVER WHERE WIDTH_BUCKET WINDOW WITH WITHIN WITHOUT YEAR").split(" "));

    @Test
    void keywordsAreTheWordsThatTheParserReservesBeyondSql2003() throws Exception {
        Set<String> reserved = new TreeSet<>();
        for (String image : CCJSqlParserConstants.tokenImage) {
            // The image of a keyword is the name of its token, as <K_SELECT>.
            if (image.matches("<K_[A-Z][A-Z0-9_]*>")) {
                String word = image.substring(3, image.length() - 1);
                if (!SQL_2003.contains(word) && (!parses("SELECT * FROM " + word + ".t")
                        || !parses("SELECT " + word + " FROM t"))) {
                    reserved.add(word);
                }
            }
        }
        try (Connection connection = DriverManager.getConnection("jdbc:fyrql:")) {
            assertEquals(String.join(",", reserved), connection.getMetaData().getSQLKeywords());
        }
    }

    /** Whether the parser takes the statement; an error of a later stage, such as an unsupported form, counts. */
    private static boolean parses(String sql) {
        try {
            SqlParser.parseStatement(sql);
            return true;
        } catch (SqlException e) {
            return !e.getMessage().startsWith("Syntax error");
        }
    }
}
