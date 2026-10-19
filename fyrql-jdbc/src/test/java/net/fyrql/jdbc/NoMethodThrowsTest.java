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

import net.fyrql.FyrQL;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tools such as DBeaver ask a driver many questions and stop at the first
 * one that throws. Every method that asks must answer: all methods of
 * {@link DatabaseMetaData}, with arguments that match everything, and the
 * getters without arguments of the connection, statements and result
 * sets, and those of {@link ResultSetMetaData} for a column. Only the
 * methods of {@link #ALLOWED} may throw, and only
 * {@link SQLFeatureNotSupportedException}.
 */
class NoMethodThrowsTest {

    /** The methods that may tell that they are not supported, as {@code Interface.method}. */
    private static final Set<String> ALLOWED = Set.of("ResultSet.getCursorName");

    /** Calls the methods of an interface that a predicate selects, and returns the failures. */
    private static List<String> call(Class<?> type, Object target, Predicate<Method> selected) {
        List<String> failures = new ArrayList<>();
        List<Method> methods = Arrays.stream(type.getMethods())
                .filter(m -> !m.getName().equals("unwrap") && !m.getName().equals("isWrapperFor"))
                .filter(m -> !Modifier.isStatic(m.getModifiers()))
                .filter(selected)
                .sorted(Comparator.comparing(Method::toString))
                .toList();
        assertTrue(methods.size() > 5, type.getSimpleName());
        for (Method m : methods) {
            String name = type.getSimpleName() + "." + m.getName();
            try {
                m.invoke(target, arguments(m));
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (!(ALLOWED.contains(name) && cause instanceof SQLFeatureNotSupportedException)) {
                    failures.add(name + Arrays.toString(m.getParameterTypes()) + ": " + cause);
                }
            } catch (ReflectiveOperationException e) {
                failures.add(name + ": " + e);
            }
        }
        return failures;
    }

    /** Returns arguments that match everything: {@code null}, zero and {@code false}, and column 1. */
    private static Object[] arguments(Method m) {
        Class<?>[] types = m.getParameterTypes();
        Object[] arguments = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == int.class) {
                arguments[i] = m.getDeclaringClass() == ResultSetMetaData.class ? 1 : 0;
            } else if (types[i] == boolean.class) {
                arguments[i] = false;
            }
        }
        return arguments;
    }

    private static boolean getter(Method m) {
        return m.getParameterCount() == 0 && (m.getName().startsWith("get") || m.getName().startsWith("is"));
    }

    @Test
    void everyMethodThatAsksAnswers() throws Exception {
        try (FyrQL engine = FyrQL.builder().register(new ConnectionTest.CountingConnector("shop"))
                .register(AllTypesTable.connector("all")).build();
                Connection c = FyrQLDriver.connect(engine, false)) {
            List<String> failures = new ArrayList<>();
            DatabaseMetaData md = c.getMetaData();
            failures.addAll(call(DatabaseMetaData.class, md, m -> true));
            failures.addAll(call(Connection.class, c, NoMethodThrowsTest::getter));
            try (Statement s = c.createStatement()) {
                failures.addAll(call(Statement.class, s, NoMethodThrowsTest::getter));
            }
            try (PreparedStatement p = c.prepareStatement("SELECT * FROM \"all\".t WHERE i > ?")) {
                failures.addAll(call(PreparedStatement.class, p, NoMethodThrowsTest::getter));
            }
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM \"all\".t")) {
                failures.addAll(call(ResultSet.class, rs, NoMethodThrowsTest::getter));
                failures.addAll(call(ResultSetMetaData.class, rs.getMetaData(), m -> true));
                rs.next();
                failures.addAll(call(ResultSet.class, rs, NoMethodThrowsTest::getter));
            }
            try (ResultSet rs = md.getColumns(null, null, null, null)) {
                rs.next();
                failures.addAll(call(ResultSet.class, rs, NoMethodThrowsTest::getter));
                failures.addAll(call(ResultSetMetaData.class, rs.getMetaData(), m -> true));
            }
            assertEquals(List.of(), failures);
        }
    }
}
