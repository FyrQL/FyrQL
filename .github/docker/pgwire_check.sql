-- The smoke test of the image of the PostgreSQL wire protocol server, run by psql in the
-- database hsql: a CSV file of the data directory, and a table that the JDBC driver from
-- the drivers directory writes and reads. Its output must be that of pgwire_check.out.
SELECT count(*), sum(n) FROM files.main.numbers;
CREATE TABLE copied AS SELECT * FROM files.main.numbers;
SELECT count(*), sum(n) FROM copied;
SELECT 1;
