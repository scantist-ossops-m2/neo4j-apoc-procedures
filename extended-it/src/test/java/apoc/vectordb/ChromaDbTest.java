package apoc.vectordb;

import apoc.util.TestUtil;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.neo4j.test.rule.DbmsRule;
import org.neo4j.test.rule.ImpermanentDbmsRule;
import org.testcontainers.chromadb.ChromaDBContainer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static apoc.util.MapUtil.map;
import static apoc.util.TestUtil.testCall;
import static apoc.util.TestUtil.testResult;
import static apoc.vectordb.VectorDbHandler.Type.CHROMA;
import static apoc.vectordb.VectorDbTestUtil.assertBerlinResult;
import static apoc.vectordb.VectorDbTestUtil.assertLondonResult;
import static apoc.vectordb.VectorDbTestUtil.assertNodesCreated;
import static apoc.vectordb.VectorDbTestUtil.assertRelsCreated;
import static apoc.vectordb.VectorDbTestUtil.dropAndDeleteAll;
import static apoc.vectordb.VectorDbTestUtil.EntityType.*;
import static apoc.vectordb.VectorEmbeddingConfig.ALL_RESULTS_KEY;
import static apoc.vectordb.VectorEmbeddingConfig.MAPPING_KEY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class ChromaDbTest {
    private static final AtomicReference<String> COLL_ID = new AtomicReference<>();
    private static final ChromaDBContainer CHROMA_CONTAINER = new ChromaDBContainer("chromadb/chroma:0.4.25.dev137");

    private static String HOST;
    
    @ClassRule
    public static DbmsRule db = new ImpermanentDbmsRule();

    @BeforeClass
    public static void setUp() throws Exception {
        CHROMA_CONTAINER.start();

        HOST = "localhost:" + CHROMA_CONTAINER.getMappedPort(8000);
        TestUtil.registerProcedure(db, ChromaDb.class, VectorDb.class);
        
        testCall(db, "CALL apoc.vectordb.chroma.createCollection($host, 'test_collection', 'cosine', 4)",
            map("host", HOST),
                r -> {
                    Map value = (Map) r.get("value");
                    COLL_ID.set((String) value.get("id"));
                });

        testCall(db, """
                        CALL apoc.vectordb.chroma.upsert($host, $collection,
                        [
                            {id: '1', vector: [0.05, 0.61, 0.76, 0.74], metadata: {city: "Berlin", foo: "one"}, text: 'ajeje'},
                            {id: '2', vector: [0.19, 0.81, 0.75, 0.11], metadata: {city: "London", foo: "two"}, text: 'brazorf'}
                        ])
                        """,
                map("host", HOST, "collection", COLL_ID.get()),
                r -> {
                    assertNull(r.get("value"));
                });
    }

    @AfterClass
    public static void tearDown() throws Exception {
        testCall(db, "CALL apoc.vectordb.chroma.deleteCollection($host, 'test_collection')",
                map("host", HOST),
                r -> {
                    Map value = (Map) r.get("value");
                    assertNull(value);
                });
    }

    @Before
    public void before() {
        dropAndDeleteAll(db);
    }
    
    @Test
    public void getVectors() {
        testResult(db, "CALL apoc.vectordb.chroma.get($host, $collection, ['1'], $conf) ",
                map("host", HOST, "collection", COLL_ID.get(), "conf", map(ALL_RESULTS_KEY, true)),
                r -> {
                    Map<String, Object> row = r.next();
                    assertBerlinResult(row, FALSE);
                    assertNotNull(row.get("vector"));
                    assertEquals("ajeje", row.get("text"));
                });
    }

    @Test
    public void getVectorsWithoutVectorResult() {
        testResult(db, "CALL apoc.vectordb.chroma.get($host, $collection, ['1'])",
                map("host", HOST, "collection", COLL_ID.get()),
                r -> {
                    Map<String, Object> row = r.next();
                    assertEquals(Map.of("city", "Berlin", "foo", "one"), row.get("metadata"));
                    assertNull(row.get("vector"));
                    assertNull(row.get("id"));
                });
    }
    
    @Test
    public void deleteVector() {
        testCall(db, """
                        CALL apoc.vectordb.chroma.upsert($host, $collection,
                        [
                            {id: 3, embedding: [0.19, 0.81, 0.75, 0.11], metadata: {foo: "baz"}}
                        ])
                        """,
                map("host", HOST, "collection", COLL_ID.get()),
                r -> {
                    assertNull(r.get("value"));
                });

        testCall(db, "CALL apoc.vectordb.chroma.delete($host, $collection, [3]) ",
                map("host", HOST, "collection", COLL_ID.get()),
                r -> {
                    assertEquals(List.of("3"), r.get("value"));
                });
    }

    @Test
    public void createAndDeleteVector() {
        testResult(db, "CALL apoc.vectordb.chroma.get($host, $collection, ['1'], $conf) ",
                map("host", HOST, "collection", COLL_ID.get(), "conf", map(ALL_RESULTS_KEY, true)),
                r -> {
                    Map<String, Object> row = r.next();
                    assertBerlinResult(row, FALSE);
                    assertNotNull(row.get("vector"));
                });
    }

    @Test
    public void queryVectors() {
        testResult(db, "CALL apoc.vectordb.chroma.query($host, $collection, [0.2, 0.1, 0.9, 0.7], {}, 5, $conf)",
                map("host", HOST, "collection", COLL_ID.get(), "conf", map(ALL_RESULTS_KEY, true)),
                r -> {
                    Map<String, Object> row = r.next();
                    assertBerlinResult(row, FALSE);
                    assertNotNull(row.get("score"));
                    assertNotNull(row.get("vector"));

                    row = r.next();
                    assertLondonResult(row, FALSE);
                    assertNotNull(row.get("score"));
                    assertNotNull(row.get("vector"));
                });
    }
    
    @Test
    public void queryVectorsWithoutVectorResult() {
        testResult(db, "CALL apoc.vectordb.chroma.query($host, $collection, [0.2, 0.1, 0.9, 0.7], {}, 5) " +
                       " YIELD score, vector, id, metadata, node RETURN * ORDER BY id",
                map("host", HOST, "collection", COLL_ID.get()),
                r -> {
                    Map<String, Object> row = r.next();
                    assertEquals(Map.of("city", "Berlin", "foo", "one"), row.get("metadata"));
                    assertNotNull(row.get("score"));
                    assertNull(row.get("vector"));
                    assertNull(row.get("id"));

                    row = r.next();
                    assertEquals(Map.of("city", "London", "foo", "two"), row.get("metadata"));
                    assertNotNull(row.get("score"));
                    assertNull(row.get("vector"));
                    assertNull(row.get("id"));
                });
    }

    @Test
    public void queryVectorsWithYield() {
        testResult(db, "CALL apoc.vectordb.chroma.query($host, $collection, [0.2, 0.1, 0.9, 0.7], {}, 5, $conf) YIELD metadata, id",
                map("host", HOST, "collection", COLL_ID.get(), "conf", map(ALL_RESULTS_KEY, true)),
                r -> {
                    assertBerlinResult(r.next(), FALSE);
                    assertLondonResult(r.next(), FALSE);
                });
    }

    @Test
    public void queryVectorsWithFilter() {
        testResult(db, """
                        CALL apoc.vectordb.chroma.query($host, $collection, [0.2, 0.1, 0.9, 0.7], {city: 'London'}, 5, $conf) YIELD metadata, id""",
                map("host", HOST, "collection", COLL_ID.get(), "conf", map(ALL_RESULTS_KEY, true)),
                r -> {
                    assertLondonResult(r.next(), FALSE);
                });
    }

    @Test
    public void queryVectorsWithLimit() {
        testResult(db, """
                        CALL apoc.vectordb.chroma.query($host, $collection, [0.2, 0.1, 0.9, 0.7], {}, 1, $conf) YIELD metadata, id""",
                map("host", HOST, "collection", COLL_ID.get(), "conf", map(ALL_RESULTS_KEY, true)),
                r -> {
                    assertBerlinResult(r.next(), FALSE);
                });
    }

    @Test
    public void queryVectorsWithCreateNode() {
        Map<String, Object> conf = map(ALL_RESULTS_KEY, true,
                MAPPING_KEY, map("embeddingProp", "vect",
                "label", "Test",
                "prop", "myId",
                "id", "foo",
                "create", true));
        
        testResult(db, "CALL apoc.vectordb.chroma.query($host, $collection, [0.2, 0.1, 0.9, 0.7], {}, 5, $conf)",
                map("host", HOST, "collection", COLL_ID.get(), "conf", conf),
                r -> {
                    Map<String, Object> row = r.next();
                    assertBerlinResult(row, NODE);
                    assertNotNull(row.get("score"));
                    assertNotNull(row.get("vector"));

                    row = r.next();
                    assertLondonResult(row, NODE);
                    assertNotNull(row.get("score"));
                    assertNotNull(row.get("vector"));
                });

        assertNodesCreated(db);

        testResult(db, "CALL apoc.vectordb.chroma.query($host, $collection, [0.22, 0.11, 0.99, 0.17], {}, 5, $conf) " +
                       "   YIELD score, vector, id, metadata, node RETURN * ORDER BY id",
                map("host", HOST, "collection", COLL_ID.get(), "conf", conf),
                r -> {
                    Map<String, Object> row = r.next();
                    assertBerlinResult(row, NODE);
                    assertNotNull(row.get("score"));
                    assertNotNull(row.get("vector"));

                    row = r.next();
                    assertLondonResult(row, NODE);
                    assertNotNull(row.get("score"));
                    assertNotNull(row.get("vector"));
                });

        assertNodesCreated(db);
    }

    @Test
    public void queryVectorsWithCreateNodeUsingExistingNode() {

        db.executeTransactionally("CREATE (:Test {myId: 'one'}), (:Test {myId: 'two'})");

        Map<String, Object> conf = map(ALL_RESULTS_KEY, true,
                MAPPING_KEY, map("embeddingProp", "vect",
                "label", "Test",
                "prop", "myId",
                "id", "foo"));
        testResult(db, "CALL apoc.vectordb.chroma.query($host, $collection, [0.2, 0.1, 0.9, 0.7], {}, 5, $conf)",
                map("host", HOST, "collection", COLL_ID.get(), "conf", conf),
                r -> {
                    Map<String, Object> row = r.next();
                    assertBerlinResult(row, NODE);
                    assertNotNull(row.get("score"));
                    assertNotNull(row.get("vector"));

                    row = r.next();
                    assertLondonResult(row, NODE);
                    assertNotNull(row.get("score"));
                    assertNotNull(row.get("vector"));
                });

        assertNodesCreated(db);
    }

    @Test
    public void queryVectorsWithCreateRel() {

        db.executeTransactionally("CREATE (:Start)-[:TEST {myId: 'one'}]->(:End), (:Start)-[:TEST {myId: 'two'}]->(:End)");

        Map<String, Object> conf = map(ALL_RESULTS_KEY, true,
                MAPPING_KEY, map("embeddingProp", "vect",
                "type", "TEST",
                "prop", "myId",
                "id", "foo",
                "create", true));
        testResult(db, "CALL apoc.vectordb.chroma.query($host, $collection, [0.2, 0.1, 0.9, 0.7], {}, 5, $conf)",
                map("host", HOST, "collection", COLL_ID.get(), "conf", conf),
                r -> {
                    Map<String, Object> row = r.next();
                    assertBerlinResult(row, REL);
                    assertNotNull(row.get("score"));
                    assertNotNull(row.get("vector"));

                    row = r.next();
                    assertLondonResult(row, REL);
                    assertNotNull(row.get("score"));
                    assertNotNull(row.get("vector"));
                });

        assertRelsCreated(db);
    }

    @Test
    public void queryVectorsWithSystemDbStorage() {
        db.executeTransactionally("CALL apoc.vectordb.store($vectorName, $host, $credential, $mapping)",
                map("vectorName", CHROMA.toString(),
                        "host", "http://" + HOST,
                        "credential", null,
                        "mapping", map("embeddingProp", "vect",
                                "label", "Test",
                                "prop", "myId",
                                "id", "foo"))
        );

        db.executeTransactionally("CREATE (:Test {myId: 'one'}), (:Test {myId: 'two'})");

        testResult(db, "CALL apoc.vectordb.chroma.query($host, $collection, [0.2, 0.1, 0.9, 0.7], {}, 5, $conf)",
                map("host", null, "collection", COLL_ID.get(), "conf", map(ALL_RESULTS_KEY, true)),
                r -> {
                    Map<String, Object> row = r.next();
                    assertBerlinResult(row, NODE);
                    assertNotNull(row.get("score"));
                    assertNotNull(row.get("vector"));

                    row = r.next();
                    assertLondonResult(row, NODE);
                    assertNotNull(row.get("score"));
                    assertNotNull(row.get("vector"));
                });

        assertNodesCreated(db);
    }
}