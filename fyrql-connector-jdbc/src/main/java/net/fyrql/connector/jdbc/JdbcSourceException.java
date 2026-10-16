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

package net.fyrql.connector.jdbc;

/**
 * A failure of a database behind a JDBC source, or a value it returns that
 * does not fit the type of its column. The message names the source and,
 * where there is one, the statement.
 */
public final class JdbcSourceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JdbcSourceException(String message, Throwable cause) {
        super(message, cause);
    }

    public JdbcSourceException(String message) {
        super(message);
    }
}
