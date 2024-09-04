package apoc.ml;

import apoc.coll.Coll;
import apoc.meta.Meta;
import apoc.text.Strings;
import apoc.util.TestUtil;
import apoc.util.Util;
import apoc.util.collection.Iterators;
import org.apache.commons.lang3.StringUtils;
import org.assertj.core.api.Assertions;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.neo4j.graphdb.Transaction;
import org.neo4j.test.rule.DbmsRule;
import org.neo4j.test.rule.ImpermanentDbmsRule;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static apoc.ml.OpenAI.GPT_4O_MODEL;
import static apoc.ml.OpenAIIT.GPT_35_MODEL;
import static apoc.ml.Prompt.API_KEY_CONF;
import static apoc.ml.MLUtil.MODEL_CONF_KEY;
import static apoc.ml.Prompt.UNKNOWN_ANSWER;
import static apoc.ml.RagConfig.*;
import static apoc.util.MapUtil.map;
import static apoc.util.TestUtil.testCall;
import static apoc.util.TestUtil.testResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.Assert.fail;

public class PromptIT {

    private static final String OPENAI_KEY = System.getenv("OPENAI_KEY");
    private static final List<String> RAG_ATTRIBUTES = List.of("name", "country", "medal", "title", "year");
    private static final String CREATE_EMBEDDINGS_FOR_RAG = """
                MATCH path=(a:Athlete)-[medal:HAS_MEDAL]->(d:Discipline)
                WITH 'Athlete name: ' + a.name + '\\ncountry: ' + a.country + '\\nmedal: ' + medal.medal + '\\nyear: ' + d.year AS text
                WITH collect(text) AS texts
                CALL apoc.ml.openai.embedding(texts, $apiKey)
                yield embedding, text
                """;
    private static final String QUERY_RAG = """
                MATCH path=(:Athlete)-[:HAS_MEDAL]->(Discipline)
                WITH collect(path) AS paths
                CALL apoc.ml.rag(paths, $attributes, $question, $conf) YIELD value
                RETURN value
                """;

    @Rule
    public DbmsRule db = new ImpermanentDbmsRule();

    @BeforeClass
    public static void check() {
        Assume.assumeNotNull("No OPENAI_KEY environment configured", OPENAI_KEY);
    }

    @Before
    public void setUp() {
        TestUtil.registerProcedure(db, Prompt.class, OpenAI.class, Meta.class, Strings.class, Coll.class);
        String movies = Util.readResourceFile("movies.cypher");
        try (Transaction tx = db.beginTx()) {
            tx.execute(movies);
            tx.commit();
        }
        
        String rag = Util.readResourceFile("rag.cypher");
        try (Transaction tx = db.beginTx()) {
            tx.execute(rag);
            tx.commit();
        }

    }
    
    @After
    public void after() {
        db.executeTransactionally("MATCH (n) DETACH DELETE n");
    }

    @Test
    public void testQuery() {
        testResult(db, """
                CALL apoc.ml.query($query, {retries: $retries, apiKey: $apiKey})
                """,
                Map.of(
                        "query", "What movies has Tom Hanks acted in?",
                        "retries", 3L,
                        "apiKey", OPENAI_KEY
                ),
                (r) -> {
                    List<Map<String, Object>> list = r.stream().toList();
                    Assertions.assertThat(list).hasSize(12);
                    Assertions.assertThat(list.stream()
                                    .map(m -> m.get("query"))
                                    .filter(Objects::nonNull)
                                    .map(Object::toString)
                                    .map(String::trim))
                            .isNotEmpty();
                });
    }

    @Test
    public void testQueryGpt35Turbo() {
        testResult(db, """
                CALL apoc.ml.query($query, {model: 'gpt-3.5-turbo', retries: $retries, apiKey: $apiKey})
                """,
                Map.of(
                        "query", "What movies has Tom Hanks acted in?",
                        "retries", 2L,
                        "apiKey", OPENAI_KEY
                ),
                (r) -> {
                    List<Map<String, Object>> list = r.stream().toList();
                    Assertions.assertThat(list).hasSize(12);
                    Assertions.assertThat(list.stream()
                                    .map(m -> m.get("query"))
                                    .filter(Objects::nonNull)
                                    .map(Object::toString)
                                    .map(String::trim))
                            .isNotEmpty();
                });
    }

    @Test
    public void testQueryGpt35TurboUsingRetryWithError() {
        testResult(db, """
                CALL apoc.ml.query($query, {model: 'gpt-3.5-turbo', retries: $retries, apiKey: $apiKey, retryWithError: true})
                """,
                Map.of(
                        "query", UUID.randomUUID().toString(),
                        "retries", 10L,
                        "apiKey", OPENAI_KEY
                ),
                (r) -> {
                    // check that it returns a Cypher result, also empty, without errors
                    List<Map<String, Object>> maps = Iterators.asList(r);
                    assertNotNull(maps);
                });
    }

    @Test
    public void testQueryUsingRetryWithError() {
        testResult(db, """
                CALL apoc.ml.query($query, {retries: $retries, apiKey: $apiKey, retryWithError: true})
                """,
                Map.of(
                        "query", UUID.randomUUID().toString(),
                        "retries", 10L,
                        "apiKey", OPENAI_KEY
                ),
                (r) -> {
                    // check that it returns a Cypher result, also empty, without errors
                    List<Map<String, Object>> maps = Iterators.asList(r);
                    assertNotNull(maps);
                });
    }

    @Test
    public void testSchema() {
        testResult(db, """
                CALL apoc.ml.schema({apiKey: $apiKey})
                """,
                Map.of(
                        "apiKey", OPENAI_KEY
                ),
                (r) -> {
                    List<Map<String, Object>> list = r.stream().toList();
                    Assertions.assertThat(list).hasSize(1);
                });
    }

    @Test
    public void testCypher() {
        long numOfQueries = 4L;
        testResult(db, """
                CALL apoc.ml.cypher($query, {count: $numOfQueries, apiKey: $apiKey})
                """,
                Map.of(
                        "query", "Who are the actors which also directed a movie?",
                        "numOfQueries", numOfQueries,
                        "apiKey", OPENAI_KEY
                ),
                (r) -> {
                    List<Map<String, Object>> list = r.stream().toList();
                    Assertions.assertThat(list).hasSize((int) numOfQueries);
                    Assertions.assertThat(list.stream()
                                    .map(m -> m.get("query"))
                                    .filter(Objects::nonNull)
                                    .map(Object::toString)
                                    .filter(StringUtils::isNotEmpty))
                            .hasSize((int) numOfQueries);
                });
    }

    @Test
    public void testCypherGpt35Turbo() {
        long numOfQueries = 4L;
        testResult(db, """
                CALL apoc.ml.cypher($query, {model: 'gpt-3.5-turbo', count: $numOfQueries, apiKey: $apiKey})
                """,
                Map.of(
                        "query", "Who are the actors which also directed a movie?",
                        "numOfQueries", numOfQueries,
                        "apiKey", OPENAI_KEY
                ),
                (r) -> {
                    List<Map<String, Object>> list = r.stream().toList();
                    Assertions.assertThat(list).hasSize((int) numOfQueries);
                    Assertions.assertThat(list.stream()
                                    .map(m -> m.get("query"))
                                    .filter(Objects::nonNull)
                                    .map(Object::toString)
                                    .filter(StringUtils::isNotEmpty))
                            .hasSize((int) numOfQueries);
                });
    }

    @Test
    public void testFromCypherGpt35Turbo() {
        testCall(db, """
                CALL apoc.ml.fromCypher($query, {model: 'gpt-3.5-turbo', retries: $retries, apiKey: $apiKey})
                """,
                Map.of(
                        "query", "MATCH (p:Person {name: \"Tom Hanks\"})-[:ACTED_IN]->(m:Movie) RETURN m",
                        "retries", 2L,
                        "apiKey", OPENAI_KEY
                ),
                (r) -> {
                    String value = ( (String) r.get("value") ).toLowerCase();
                    String message = "Current value is: " + value;
                    assertTrue(message,
                            value.contains("movie"));
                    assertTrue(message,
                            value.contains("person") || value.contains("people") || value.contains("actor"));
                });
    }

    @Test
    public void testFromCypher() {
        testCall(db, """
                CALL apoc.ml.fromCypher($query, {retries: $retries, apiKey: $apiKey})
                """,
                Map.of(
                        "query", "MATCH (p:Person {name: \"Tom Hanks\"})-[:ACTED_IN]->(m:Movie) RETURN m",
                        "retries", 2L,
                        "apiKey", OPENAI_KEY
                ),
                (r) -> {
                    String value = ( (String) r.get("value") ).toLowerCase();
                    String message = "Current value is: " + value;
                    assertTrue(message,
                            value.contains("movie"));
                    assertTrue(message,
                            value.contains("person") || value.contains("people") || value.contains("actor"));
                });
    }

    @Test
    public void testSchemaFromQueriesGpt35Turbo() {
        List<String> queries = List.of("MATCH p=(n:Movie)--() RETURN p", "MATCH (n:Person) RETURN n", "MATCH (n:Movie) RETURN n", "MATCH p=(n)-[r]->() RETURN r");

        testCall(db, """
                CALL apoc.ml.fromQueries($queries, {model: 'gpt-3.5-turbo', apiKey: $apiKey})
                """,
                Map.of(
                        "queries", queries,
                        "apiKey", OPENAI_KEY
                ),
                (r) -> {

                    String value = ((String) r.get("value")).toLowerCase();
                    Assertions.assertThat(value).containsIgnoringCase("movie");
                    Assertions.assertThat(value).containsAnyOf("person", "people");
                });
    }

    @Test
    public void testSchemaFromQueries() {
        List<String> queries = List.of("MATCH p=(n:Movie)--() RETURN p", "MATCH (n:Person) RETURN n", "MATCH (n:Movie) RETURN n", "MATCH p=(n)-[r]->() RETURN r");

        testCall(db, """
                CALL apoc.ml.fromQueries($queries, {apiKey: $apiKey})
                """,
                Map.of(
                        "queries", queries,
                        "apiKey", OPENAI_KEY
                ),
                (r) -> {

                    String value = ((String) r.get("value")).toLowerCase();
                    Assertions.assertThat(value).containsIgnoringCase("movie");
                    Assertions.assertThat(value).containsAnyOf("person", "people");
                });
    }

    @Test
    public void testSchemaFromQueriesWithSingleQueryGpt35Turbo() {
        List<String> queries = List.of("MATCH (n:Movie) RETURN n");

        testCall(db, """
                CALL apoc.ml.fromQueries($queries, {model: 'gpt-3.5-turbo', apiKey: $apiKey})
                """,
                Map.of(
                        "queries", queries,
                        "apiKey", OPENAI_KEY
                ),
                (r) -> {
                    String value = ((String) r.get("value")).toLowerCase();
                    Assertions.assertThat(value).containsIgnoringCase("movie");
                    Assertions.assertThat(value).doesNotContainIgnoringCase("person", "people");
                });
    }
    
    @Test
    public void testSchemaFromQueriesWithSingleQuery() {
        List<String> queries = List.of("MATCH (n:Movie) RETURN n");

        testCall(db, """
                CALL apoc.ml.fromQueries($queries, {apiKey: $apiKey})
                """,
                Map.of(
                        "queries", queries,
                        "apiKey", OPENAI_KEY
                ),
                (r) -> {
                    String value = ((String) r.get("value")).toLowerCase();
                    Assertions.assertThat(value).containsIgnoringCase("movie");
                    Assertions.assertThat(value).doesNotContainIgnoringCase("person", "people");
                });
    }

    @Test
    public void testSchemaFromQueriesWithWrongQueryGpt35Turbo() {
        List<String> queries = List.of("MATCH (n:Movie) RETURN a");
        try {
            testCall(db, """
                CALL apoc.ml.fromQueries($queries, {model: 'gpt-3.5-turbo', apiKey: $apiKey})
                """,
                    Map.of(
                            "queries", queries,
                            "apiKey", OPENAI_KEY
                    ),
                    (r) -> fail());
        } catch (Exception e) {
            Assertions.assertThat(e.getMessage()).contains(" Variable `a` not defined");
        }

    }

    @Test
    public void testSchemaFromQueriesWithWrongQuery() {
        List<String> queries = List.of("MATCH (n:Movie) RETURN a");
        try {
            testCall(db, """
                CALL apoc.ml.fromQueries($queries, {apiKey: $apiKey})
                """,
                    Map.of(
                            "queries", queries,
                            "apiKey", OPENAI_KEY
                    ),
                    (r) -> fail());
        } catch (Exception e) {
            Assertions.assertThat(e.getMessage()).contains(" Variable `a` not defined");
        }

    }

    @Test
    public void testSchemaFromEmptyQueriesGpt35Turbo() {
        List<String> queries = List.of("MATCH (n:Movie) RETURN 1");

        testCall(db, """
            CALL apoc.ml.fromQueries($queries, {model: 'gpt-3.5-turbo', apiKey: $apiKey})
            """,
                Map.of(
                        "queries", queries,
                        "apiKey", OPENAI_KEY
                ),
                (r) -> {
                    String value = ((String) r.get("value")).toLowerCase();
                    Assertions.assertThat(value).containsAnyOf("does not contain", "empty", "undefined", "doesn't have");
                });
    }
    
    @Test
    public void testSchemaFromEmptyQueries() {
        List<String> queries = List.of("MATCH (n:Movie) RETURN 1");
        
        testCall(db, """
            CALL apoc.ml.fromQueries($queries, {apiKey: $apiKey})
            """,
                Map.of(
                        "queries", queries,
                        "apiKey", OPENAI_KEY
                ),
                (r) -> {
                    String value = ((String) r.get("value")).toLowerCase();
                    Assertions.assertThat(value).containsAnyOf("does not contain", "empty", "undefined", "doesn't have");
                });
    }

    @Test
    public void ragWithRelevantAttributesComparedToIrrelevantOneAndChatProcedure() {
        ragWithRelevantAttributesCommon(Map.of());
    }

    @Test
    public void ragWithRelevantAttributesComparedToIrrelevantOneAndChatProcedureGpt35Turbo() {
        ragWithRelevantAttributesCommon(Map.of(MODEL_CONF_KEY, GPT_35_MODEL));
    }

    @Test
    public void ragWithIrrelevantAttributesAndCustomPrompt() {
        String customUnknownAnswer = "Absolutely no idea :/";
        String prompt = """
                You are a customer service agent that helps a customer with answering questions about a service.
                Use the following context to answer the `user question` at the end.
                If you don't know the answer, just say `%s`, don't try to make up an answer.
                """.formatted(customUnknownAnswer);
        
        testCall(db, QUERY_RAG,
                map("attributes", List.of("irrelevant", "irrelevant2"),
                        "question", "Which athletes won the gold medal in curling at the 2022 Winter Olympics?",
                        "conf", map(API_KEY_CONF, OPENAI_KEY, 
                                PROMPT_CONF, prompt
                        )
                ),
                (r) -> {
                    String value = (String) r.get("value");
                    String message = "Current value is: " + value;
                    assertTrue(message, value.contains(customUnknownAnswer));

                    assertNot2022Winners(value);
                });
    }
    
    @Test
    public void testRagWithVariousQuestions() {
        testCall(db, QUERY_RAG,
                map("attributes", RAG_ATTRIBUTES,
                        "question", "Which athletes won the gold medal in curling at the 2018 Winter Olympics?",
                        "conf", map(API_KEY_CONF, OPENAI_KEY)
                ),
                (r) -> {
                    String value = (String) r.get("value");
                    assertThat(value).contains("Lawes", "Morris", "USA");
                    assertNot2022Winners(value);
                });

        testCall(db, QUERY_RAG,
                map("attributes", RAG_ATTRIBUTES,
                        "question", "Which athletes won the silver medal in curling at the 2022 Winter Olympics?",
                        "conf", map(API_KEY_CONF, OPENAI_KEY)
                ),
                (r) -> {
                    String value = (String) r.get("value");
                    assertThat(value).contains("Kristin Skaslien", "Magnus Nedregotten", "Norway");
                    assertNot2022Winners(value);
                });
    }
    
    @Test
    public void testRagQueryString() {
        testCall(db, QUERY_RAG,
                map(
                        "query", "MATCH path=(:Athlete)-[:HAS_MEDAL]->(Discipline) RETURN path",
                        "attributes", RAG_ATTRIBUTES,
                        "question", "Which athletes won the gold medal in curling at the 2022 Winter Olympics?",
                        "conf", map(API_KEY_CONF, OPENAI_KEY)
                ),
                (r) -> {
                    String value = (String) r.get("value");
                    assert2022Winners(value);
                });
    }

    @Test
    public void testRagEmbedding() {
        // create vector index and node embeddings
        String indexName = "rag-embeddings";
        db.executeTransactionally("""
                CREATE VECTOR INDEX `%s`
                FOR (n:RagEmbedding) ON (n.embedding)
                OPTIONS {indexConfig: {
                 `vector.dimensions`: 1536,
                 `vector.similarity_function`: 'cosine'
                }}"""
                .formatted(indexName)
        );

        db.executeTransactionally(CREATE_EMBEDDINGS_FOR_RAG + "\nCREATE (:RagEmbedding {text: text, embedding: embedding})",
                map("apiKey", OPENAI_KEY)
        );

        Map<String, Object> conf = map(API_KEY_CONF, OPENAI_KEY,
                EMBEDDINGS_CONF, EmbeddingQuery.Type.NODE.name(),
                TOP_K_CONF, 10);
        
        testCall(db, "CALL apoc.ml.rag($query, $attributes, $question, $conf)",
                map(
                        "query", indexName,
                        "attributes", List.of("text"),
                        "question", "Which athletes won the gold medal in curling at the 2022 Winter Olympics?",
                        "conf", conf
                ),
                (r) -> {
                    String value = (String) r.get("value");
                    assert2022Winners(value);
                });
    }

    @Test
    public void testRagEmbeddingWithRels() {
        // create vector index and rels embeddings
        String indexName = "rag-rel-embeddings";
        db.executeTransactionally("""
                CREATE VECTOR INDEX `%s`
                FOR ()-[r:RAG_EMBEDDING]-() ON (r.embedding)
                OPTIONS {indexConfig: {
                 `vector.dimensions`: 1536,
                 `vector.similarity_function`: 'cosine'
                }}"""
                .formatted(indexName)
        );
        
        db.executeTransactionally(CREATE_EMBEDDINGS_FOR_RAG + "\nCREATE (:Start)-[:RAG_EMBEDDING {text: text, embedding: embedding}]->(:End)",
                map("apiKey", OPENAI_KEY)
        );

        Map<String, Object> conf = map(API_KEY_CONF, OPENAI_KEY,
                EMBEDDINGS_CONF, EmbeddingQuery.Type.NODE.name(),
                TOP_K_CONF, 10);
        try {
            testCall(db, "CALL apoc.ml.rag($query, $attributes, $question, $conf)",
                    map(
                            "query", indexName,
                            "attributes", List.of("text"),
                            "question", "Which athletes won the gold medal in curling at the 2022 Winter Olympics?",
                            "conf", conf
                    ),
                    (r) -> fail());
        } catch (Exception e) {
            assertThat(e.getMessage()).contains("it cannot be queried for nodes");
        }

        conf.put(EMBEDDINGS_CONF, EmbeddingQuery.Type.REL.name());
        testCall(db, "CALL apoc.ml.rag($query, $attributes, $question, $conf)",
                map(
                        "query", indexName,
                        "attributes", List.of("text"),
                        "question", "Which athletes won the gold medal in curling at the 2022 Winter Olympics?",
                        "conf", conf
                ),
                (r) -> {
                    String value = (String) r.get("value");
                    assert2022Winners(value);
                });
    }

    private static void assertNot2022Winners(String value) {
        assertThat(value).doesNotContain("Stefania Constantini", "Amos Mosaner", "Italy");
    }

    private static void assert2022Winners(String value) {
        assertThat(value).contains("Stefania Constantini", "Amos Mosaner");
        assertThat(value).containsAnyOf("Italy", "Italian");
    }

    private void ragWithRelevantAttributesCommon(Map<String, Object> config) {
        String question = "Which athletes won the gold medal in mixed doubles's curling  at the 2022 Winter Olympics?";

        // -- test with hallucinations, wrong winner names
        testCall(db, """
                CALL apoc.ml.openai.chat([
                    {role:"user", content: $question}
                ], $apiKey, $conf)""",
                map("apiKey", OPENAI_KEY, "question", question, "conf", config),
                r -> {
                    var result = (Map<String,Object>) r.get("value");

                    Map message = ((List<Map<String,Map>>) result.get("choices")).get(0).get("message");
                    assertEquals("assistant", message.get("role"));
                    String value = (String) message.get("content");

                    String msg = "Current value is: " + value;
                    assertTrue(msg, value.contains("gold medal"));
                    if (config.getOrDefault(MODEL_CONF_KEY, GPT_4O_MODEL).equals(GPT_35_MODEL)) {
                        assertNot2022Winners(value);
                    } else {
                        // with gpt-40 the info are updated, so the 2022 winners are known withuout RAG
                        assert2022Winners(value);
                    }
                });

        // -- test RAG with irrelevant attributes
        testCall(db, QUERY_RAG,
                map("attributes", List.of("irrelevant", "irrelevant2"),
                        "question", "Which athletes won the gold medal in curling at the 2022 Winter Olympics?",
                        "conf", config.isEmpty() ? map(API_KEY_CONF, OPENAI_KEY) : map(API_KEY_CONF, OPENAI_KEY, MODEL_CONF_KEY, config.get(MODEL_CONF_KEY))
                ),
                (r) -> {
                    String value = (String) r.get("value");
                    String message = "Current value is: " + value;
                    assertTrue(message, value.contains(UNKNOWN_ANSWER));
                    assertNot2022Winners(value);
                });

        // -- test RAG with relevant attributes
        testCall(db, QUERY_RAG,
                map(
                        "attributes", RAG_ATTRIBUTES,
                        "question", "Which athletes won the gold medal in curling at the 2022 Winter Olympics?",
                        "conf", config.isEmpty() ? map(API_KEY_CONF, OPENAI_KEY) : map(API_KEY_CONF, OPENAI_KEY, MODEL_CONF_KEY, config.get(MODEL_CONF_KEY))
                ),
                (r) -> {
                    String value = (String) r.get("value");
                    assert2022Winners(value);
                });
    }

}