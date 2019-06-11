/*
 * Copyright 2019 GridGain Systems, Inc. and Contributors.
 *
 * Licensed under the GridGain Community Edition License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.gridgain.com/products/software/community-edition/gridgain-community-edition-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.yardstick.sql;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.ignite.IgniteDataStreamer;
import org.apache.ignite.IgniteSemaphore;
import org.apache.ignite.binary.BinaryObjectBuilder;
import org.apache.ignite.cache.query.FieldsQueryCursor;
import org.apache.ignite.cache.query.SqlFieldsQuery;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.yardstick.IgniteAbstractBenchmark;
import org.yardstickframework.BenchmarkConfiguration;

import static org.yardstickframework.BenchmarkUtils.println;

/**
 * Ignite benchmark that performs multi-stage select queries.
 */
public class IgniteSqlMemTrackerBenchmark extends IgniteAbstractBenchmark {
    /** Queries. */
    private final Map<String, String> qrys = new HashMap<>();

    /** Sql query for benchmark. */
    private String sql;

    /** Initialize step. */
    private boolean initStep;

    {
        qrys.put("SQL_LIMIT",
            "SELECT P.ID, P.NAME " +
                "FROM PERSON " +
                "LIMIT 2000");

        qrys.put("SQL_SORT_IDX",
            "SELECT P.ID, P.NAME " +
                "FROM PERSON P " +
                "ORDER BY PERSON.ID");

        qrys.put("SQL_DISTINCT",
            "SELECT P.NAME, DISTINCT P.GRP " +
                "FROM PERSON");

        qrys.put("SQL_GROUP_IDX",
            "SELECT PERSON.GRP_IDX, AVG(PERSON.SALARY), MIN(PERSON.SALARY), MAX(PERSON.SALARY) " +
                "FROM PERSON " +
                "USE INDEX (PERSON_GRP_IDX) " +
                "GROUP BY PERSON.GRP_IDX");

        qrys.put("SQL_GROUP_NON_IDX",
            "SELECT PERSON.GRP, AVG(PERSON.SALARY), MIN(PERSON.SALARY), MAX(PERSON.SALARY) " +
                "FROM PERSON " +
                "GROUP BY PERSON.GRP");

        qrys.put("SQL_GROUP_DISTINCT",
            "SELECT PERSON.GRP_IDX, COUNT(DISTINCT PERSON.NAME), MIN(PERSON.SALARY), MAX(PERSON.SALARY) " +
                "FROM PERSON " +
                "USE INDEX (PERSON_GRP_IDX) " +
                "GROUP BY PERSON.GRP_IDX");
    }

    /** {@inheritDoc} */
    @Override public void setUp(BenchmarkConfiguration cfg) throws Exception {
        super.setUp(cfg);

        String qryName = args.getStringParameter("qryName", "SQL_LIMIT");

        sql = qrys.get(qryName);

        if (sql == null) {
            throw new Exception("Invalid query name: " + qryName
                + ". Available queries: " + qrys.keySet());
        }

        initStep = args.getBooleanParameter("init", false);

        if (initStep)
            init(cfg);

        printPlan();
    }

    /**
     *
     */
    private void init(BenchmarkConfiguration cfg) {
        IgniteSemaphore sem = ignite().semaphore("sql-setup", 1, true, true);

        try {
            if (sem.tryAcquire()) {

                println(cfg, "Create tables...");

                sql("CREATE TABLE PERSON (" +
                    "ID LONG PRIMARY KEY,  " +
                    "SALARY LONG," +
                    "GRP INT, " +
                    "GRP_IDX INT, " +
                    "NAME VARCHAR)");

                println(cfg, "Create index...");

                sql("CREATE INDEX PERSON_SALARY_IDX ON PERSON(SALARY)");
                sql("CREATE INDEX PERSON_GRP_IDX ON PERSON(GRP_IDX)");

                int range = args.range();

                println(cfg, "Populate cache PERSON, range: " + range);

                try (IgniteDataStreamer stream = ignite().dataStreamer("PERSON")) {
                    stream.allowOverwrite(false);

                    for (long k = 0; k < range; ++k) {
                        BinaryObjectBuilder bob = ignite().binary().builder("PERSON");

                        int grp = ThreadLocalRandom.current().nextInt(range / 100);

                        bob.setField("ID", ThreadLocalRandom.current().nextLong());
                        bob.setField("SALARY", ThreadLocalRandom.current().nextLong(100_000));
                        bob.setField("VAL", UUID.randomUUID());
                        bob.setField("NO_IDX_ID", grp);
                        bob.setField("IDX_ID", grp);

                        stream.addData(k, bob.build());
                    }
                }
            }
        }
        finally {
            sem.release();
        }
    }

    /**
     * @param sql SQL query.
     * @param args Query parameters.
     * @return Results cursor.
     */
    private FieldsQueryCursor<List<?>> sql(String sql, Object... args) {
        return ((IgniteEx)ignite()).context().query().querySqlFields(new SqlFieldsQuery(sql)
            .setSchema("PUBLIC")
            .setLazy(true)
            .setArgs(args), false);
    }

    /** {@inheritDoc} */
    @Override public boolean test(Map<Object, Object> map) throws Exception {
        FieldsQueryCursor<List<?>> cur;

        cur = sql(sql);

        Iterator it = cur.iterator();

        long cnt = 0;

        while (it.hasNext()) {
            it.next();
            ++cnt;
        }

        return true;
    }

    /**
     *
     */
    private void printPlan() {
        FieldsQueryCursor<List<?>> planCur;

        planCur = sql("EXPLAIN " + sql);

        println("Plan: " + planCur.getAll().toString());
    }
}
