package apoc.custom;

import apoc.path.PathExplorer;
import apoc.util.FileUtils;
import apoc.util.TestUtil;
import apoc.util.collection.Iterators;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.rules.TemporaryFolder;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.test.TestDatabaseManagementServiceBuilder;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static apoc.util.MapUtil.map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * @author mh
 * @since 18.08.18
 */
public class CypherProceduresStorageTest {
    private final static String QUERY_CREATE = "RETURN $input1 + $input2 as answer";
    private final static String QUERY_OVERWRITE = "RETURN $input1 + $input2 + 123 as answer";

    @Rule
    public TemporaryFolder STORE_DIR = new TemporaryFolder();

    private GraphDatabaseService db;
    private DatabaseManagementService dbms;

    @Before
    public void setUp() throws Exception {
        dbms = new TestDatabaseManagementServiceBuilder( STORE_DIR.getRoot().toPath()).build();
        db = dbms.database( GraphDatabaseSettings.DEFAULT_DATABASE_NAME);
        TestUtil.registerProcedure(db, CypherProcedures.class, PathExplorer.class);
    }

    @AfterAll
    public void tearDown() {
        dbms.shutdown();
    }

    private void restartDb() {
        dbms.shutdown();
        dbms = new TestDatabaseManagementServiceBuilder(STORE_DIR.getRoot().toPath()).build();
        db = dbms.database(GraphDatabaseSettings.DEFAULT_DATABASE_NAME);
        assertTrue(db.isAvailable(1000));
        TestUtil.registerProcedure(db, CypherProcedures.class, PathExplorer.class);
    }

    @Test
    public void registerSimpleStatement() {
        db.executeTransactionally("CALL apoc.custom.declareProcedure('answer() :: (answer::LONG)','RETURN 42 as answer')");
        restartDb();
        TestUtil.testCall(db, "call custom.answer()", (row) -> assertEquals(42L, row.get("answer")));
        TestUtil.testCall(db, "call apoc.custom.list()", row -> {
            assertEquals("answer", row.get("name"));
            assertEquals("procedure", row.get("type"));
        });
    }

    @Test
    public void registerSimpleFunctionWithDotInName() {
        db.executeTransactionally("CALL apoc.custom.declareFunction('foo.bar.baz() :: LONG','RETURN 42 as answer')");
        TestUtil.testCall(db, "return custom.foo.bar.baz() as answer", (row) -> assertEquals(42L, row.get("answer")));
        TestUtil.testCall(db, "call apoc.custom.list()", row -> {
            assertEquals("foo.bar.baz", row.get("name"));
            assertEquals("function", row.get("type"));
        });
        restartDb();
        TestUtil.testCall(db, "return custom.foo.bar.baz() as answer", (row) -> assertEquals(42L, row.get("answer")));
        TestUtil.testCall(db, "call apoc.custom.list()", row -> {
            assertEquals("foo.bar.baz", row.get("name"));
            assertEquals("function", row.get("type"));
        });
    }

    @Test
    public void registerSimpleProcedureWithDotInName() {
        db.executeTransactionally("CALL apoc.custom.declareProcedure('foo.bar.baz() :: (answer::LONG)','RETURN 42 as answer')");
        TestUtil.testCall(db, "call custom.foo.bar.baz()", (row) -> assertEquals(42L, row.get("answer")));
        TestUtil.testCall(db, "call apoc.custom.list()", row -> {
            assertEquals("foo.bar.baz", row.get("name"));
            assertEquals("procedure", row.get("type"));
        });
        restartDb();
        TestUtil.testCall(db, "call custom.foo.bar.baz()", (row) -> assertEquals(42L, row.get("answer")));
        TestUtil.testCall(db, "call apoc.custom.list()", row -> {
            assertEquals("foo.bar.baz", row.get("name"));
            assertEquals("procedure", row.get("type"));
        });
    }

    @Test
    public void registerSimpleStatementConcreteResults() {
        db.executeTransactionally("CALL apoc.custom.declareProcedure('answer() :: (answer::LONG)','RETURN 42 as answer')");
        restartDb();
        TestUtil.testCall(db, "call custom.answer()", (row) -> assertEquals(42L, row.get("answer")));
    }

    @Test
    public void registerParameterStatement() {
        db.executeTransactionally("CALL apoc.custom.declareProcedure('answer(answer::LONG) :: (answer::LONG)','RETURN $answer as answer')");
        restartDb();
        TestUtil.testCall(db, "call custom.answer(42)", (row) -> assertEquals(42L, row.get("answer")));
    }

    @Test
    public void registerConcreteParameterStatement() {
        db.executeTransactionally("CALL apoc.custom.declareProcedure('answer(answer::LONG) :: (answer::LONG)','RETURN $answer as answer')");
        restartDb();
        TestUtil.testCall(db, "call custom.answer(42)", (row) -> assertEquals(42L, row.get("answer")));
    }

    @Test
    public void registerConcreteParameterAndReturnStatement() {
        db.executeTransactionally("CALL apoc.custom.declareProcedure('answer(input = 42 ::LONG) :: (answer::LONG)','RETURN $input as answer')");
        restartDb();
        TestUtil.testCall(db, "call custom.answer()", (row) -> assertEquals(42L, row.get("answer")));
    }

    @Test
    public void testAllParameterTypes() {
        db.executeTransactionally("CALL apoc.custom.declareProcedure('answer(int::INTEGER, float::FLOAT,string::STRING,map::MAP,listInt::LIST OF INTEGER,bool::BOOLEAN,date::DATE,datetime::DATETIME,point::POINT) :: (data::LIST OF ANY)','RETURN  [$int,$float,$string,$map,$listInt,$bool,$date,$datetime,$point] as data')");

        restartDb();
        TestUtil.testCall(db, "call custom.answer(42,3.14,'foo',{a:1},[1],true,date(),datetime(),point({x:1,y:2}))",
                (row) -> assertEquals(9, ((List) row.get("data")).size()));
    }

    @Test
    public void registerSimpleStatementFunction() {
        db.executeTransactionally("CALL apoc.custom.declareFunction('answer() :: LONG','RETURN 42 as answer')");
        TestUtil.testCall(db, "return custom.answer() as answer", (row) -> assertEquals(42L, row.get("answer")));
        restartDb();
        TestUtil.testCall(db, "return custom.answer() as answer", (row) -> assertEquals(42L, row.get("answer")));
        TestUtil.testCall(db, "call apoc.custom.list()", row -> {
            assertEquals("answer", row.get("name"));
            assertEquals("function", row.get("type"));
        });
    }

    @Test
    public void registerSimpleStatementFunctionWithDotInName() {
        db.executeTransactionally("CALL apoc.custom.declareFunction('foo.bar.baz() :: LONG','RETURN 42 as answer')");
        TestUtil.testCall(db, "return custom.foo.bar.baz() as answer", (row) -> assertEquals(42L, row.get("answer")));
        TestUtil.testCall(db, "call apoc.custom.list()", row -> {
            assertEquals("foo.bar.baz", row.get("name"));
            assertEquals("function", row.get("type"));
        });
        restartDb();
        TestUtil.testCall(db, "return custom.foo.bar.baz() as answer", (row) -> assertEquals(42L, row.get("answer")));
        TestUtil.testCall(db, "call apoc.custom.list()", row -> {
            assertEquals("foo.bar.baz", row.get("name"));
            assertEquals("function", row.get("type"));
        });
    }

    @Test
    public void registerSimpleStatementConcreteResultsFunction() {
        db.executeTransactionally("CALL apoc.custom.declareFunction('answer() :: LONG','RETURN 42 as answer')");
        restartDb();
        TestUtil.testCall(db, "return custom.answer() as answer", (row) -> assertEquals(42L, row.get("answer")));
    }

    @Test
    public void registerSimpleStatementConcreteResultsFunctionUnnamedResultColumn() {
        db.executeTransactionally("CALL apoc.custom.declareFunction('answer() :: LONG','RETURN 42 as answer')");
        restartDb();
        TestUtil.testCall(db, "return custom.answer() as answer", (row) -> assertEquals(42L, row.get("answer")));
    }

    @Test
    public void registerParameterStatementFunction() {
        db.executeTransactionally("CALL apoc.custom.declareFunction('answer(answer::LONG) :: LONG','RETURN $answer as answer')");
        restartDb();
        TestUtil.testCall(db, "return custom.answer(42) as answer", (row) -> assertEquals(42L, row.get("answer")));
    }

    @Test
    public void registerConcreteParameterAndReturnStatementFunction() {
        db.executeTransactionally("CALL apoc.custom.declareFunction('answer(input::LONG) :: LONG','RETURN $input as answer')");
        TestUtil.testCall(db, "return custom.answer(42) as answer", (row) -> assertEquals(42L, row.get("answer")));
    }

    @Test
    public void testAllParameterTypesFunction() {
        db.executeTransactionally("CALL apoc.custom.declareFunction('answer(int::INTEGER, float::FLOAT,string::STRING,map::MAP,listInt::LIST OF INTEGER,bool::BOOLEAN,date::DATE,datetime::DATETIME,point::POINT) :: LIST OF ANY','RETURN  [$int,$float,$string,$map,$listInt,$bool,$date,$datetime,$point] as data')");
        restartDb();
        TestUtil.testCall(db, "return custom.answer(42,3.14,'foo',{a:1},[1],true,date(),datetime(),point({x:1,y:2})) as data",
                (row) -> {
                    System.out.println(row);
                    assertEquals(9, ((List<List>) row.get("data")).get(0).size());
                });
    }

    @Test
    public void testIssue1744() throws Exception {
        db.executeTransactionally("CREATE (:Area {name: 'foo'})-[:CURRENT]->(:VantagePoint {alpha: 'beta'})");
        db.executeTransactionally("CALL apoc.custom.declareProcedure('vantagepoint_within_area(areaName::STRING) :: (resource::NODE)',\n" +
            "  \"MATCH (start:Area {name: $areaName} )\n" +
            "    CALL apoc.path.expand(start,'CONTAINS>|<SEES|CURRENT','',0,100) YIELD path\n" +
            "    UNWIND nodes(path) as node\n" +
            "    WITH node\n" +
            "    WHERE node:VantagePoint\n" +
            "    RETURN DISTINCT node as resource\",\n" +
            "  'READ',\n" +
            "  'Get vantage points within an area and all included areas');");

        // function analogous to procedure
        db.executeTransactionally("CALL apoc.custom.declareFunction('vantagepoint_within_area(areaName::STRING) ::NODE',\n" +
            "  \"MATCH (start:Area {name: $areaName} )\n" +
            "    CALL apoc.path.expand(start,'CONTAINS>|<SEES|CURRENT','',0,100) YIELD path\n" +
            "    UNWIND nodes(path) as node\n" +
            "    WITH node\n" +
            "    WHERE node:VantagePoint\n" +
            "    RETURN DISTINCT node as resource\");");

        testCallIssue1744();
        restartDb();

        final String logFileContent = Files.readString(new File(FileUtils.getLogDirectory(), "debug.log").toPath());
        assertFalse(logFileContent.contains("Could not register function: custom.vantagepoint_within_area"));
        assertFalse(logFileContent.contains("Could not register procedure: custom.vantagepoint_within_area"));
        testCallIssue1744();
    }

    @Test
    public void functionSignatureShouldNotChangeBeforeAndAfterRestart() {
        functionsCreation();
        functionAssertions();
        restartDb();
        functionAssertions();
    }

    @Test
    public void procedureSignatureShouldNotChangeBeforeAndAfterRestart() {
        proceduresCreation();
        procedureAssertions();
        restartDb();
        procedureAssertions();
    }

    @Test
    public void functionSignatureShouldNotChangeBeforeAndAfterRestartAndOverwrite() {
        functionsCreation();
        functionAssertions();
        restartDb();

        // overwrite function
        db.executeTransactionally("call apoc.custom.declareFunction('sumFun2(input1::INT, input2::INT) :: INT',$query)",
                map("query", QUERY_OVERWRITE));
        db.executeTransactionally("call apoc.custom.declareFunction('sumFun1(input1 = null::INT, input2 = null::INT) :: INT',$query)",
                map("query", QUERY_OVERWRITE));
        functionAssertions();
    }

    @Test
    public void procedureSignatureShouldNotChangeBeforeAndAfterRestartAndOverwrite() {
        proceduresCreation();
        procedureAssertions();
        restartDb();

        // overwrite function
        db.executeTransactionally("call apoc.custom.declareProcedure('sum1(input1 = null::INT, input2 = null::INT) :: (answer::INT)',$query)",
                map("query", QUERY_OVERWRITE));
        db.executeTransactionally("call apoc.custom.declareProcedure('sum2(input1::INT, input2::INT) :: (answer::INT)',$query)",
                map("query", QUERY_OVERWRITE));
        procedureAssertions();
    }

    private void functionsCreation() {
        // 1 declareFunction with default null, 1 declareFunction without default
        db.executeTransactionally("call apoc.custom.declareFunction('sumFun1(input1 = null::INT, input2 = null::INT) :: INT',$query)",
                map("query", QUERY_CREATE));
        db.executeTransactionally("call apoc.custom.declareFunction('sumFun2(input1::INT, input2::INT) :: INT',$query)",
                map("query", QUERY_CREATE));
    }

    private void functionAssertions() {
        TestUtil.testResult(db, "SHOW FUNCTIONS YIELD signature, name WHERE name STARTS WITH 'custom.sumFun' RETURN DISTINCT name, signature ORDER BY name",
                r -> {
                    Map<String, Object> row = r.next();
                    assertEquals("custom.sumFun1(input1 = null :: INTEGER?, input2 = null :: INTEGER?) :: (INTEGER?)", row.get("signature"));
                    row = r.next();
                    assertEquals("custom.sumFun2(input1 :: INTEGER?, input2 :: INTEGER?) :: (INTEGER?)", row.get("signature"));
                    assertFalse(r.hasNext());
                });
        TestUtil.testResult(db, "call apoc.custom.list() YIELD name RETURN name ORDER BY name",
                row -> {
                    final List<String> sumFun1 = List.of("sumFun1", "sumFun2");
                    assertEquals(sumFun1, Iterators.asList(row.columnAs("name")));
                });

        TestUtil.testCall(db, String.format("RETURN %s()", "custom.sumFun1"), (row) -> assertNull(row.get("answer")));
        try {
            TestUtil.testCall(db, String.format("RETURN %s()", "custom.sumFun2"), (row) -> fail("Should fail because of missing params"));
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("Function call does not provide the required number of arguments: expected 2 got 0"));
        }

    }

    private void proceduresCreation() {
        // 1 declareProcedure with default null, 1 declareProcedure without default
        db.executeTransactionally("call apoc.custom.declareProcedure('sum1(input1 = null::INT, input2 = null::INT) :: (answer::INT)',$query)",
                map("query", QUERY_CREATE));
        db.executeTransactionally("call apoc.custom.declareProcedure('sum2(input1::INT, input2::INT) :: (answer::INT)',$query)",
                map("query", QUERY_CREATE));
    }

    private void procedureAssertions() {
        TestUtil.testResult(db, "SHOW PROCEDURES YIELD signature, name WHERE name STARTS WITH 'custom.sum' RETURN DISTINCT name, signature ORDER BY name",
                r -> {
                    Map<String, Object> row = r.next();
                    assertEquals("custom.sum1(input1 = null :: INTEGER?, input2 = null :: INTEGER?) :: (answer :: INTEGER?)", row.get("signature"));
                    row = r.next();
                    assertEquals("custom.sum2(input1 :: INTEGER?, input2 :: INTEGER?) :: (answer :: INTEGER?)", row.get("signature"));
                    assertFalse(r.hasNext());
                });
        TestUtil.testResult(db, "call apoc.custom.list() YIELD name RETURN name ORDER BY name",
                row -> {
                    final List<String> sumFun1 = List.of("sum1", "sum2");
                    assertEquals(sumFun1, Iterators.asList(row.columnAs("name")));
                });

        TestUtil.testCall(db, String.format("CALL %s", "custom.sum1"), (row) -> assertNull(row.get("answer")));
        try {
            TestUtil.testCall(db, String.format("CALL %s()", "custom.sum2"), (row) -> fail("Should fail because of missing params"));
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("Procedure call does not provide the required number of arguments: got 0 expected at least 2"));
        }
    }

    private void testCallIssue1744() {
        TestUtil.testCall(db, "CALL custom.vantagepoint_within_area('foo')", this::assertCallIssue1744);
        TestUtil.testCall(db, "RETURN custom.vantagepoint_within_area('foo') as resource", this::assertCallIssue1744);
    }

    private void assertCallIssue1744(Map<String, Object> row) {
        final Node resource = (Node) row.get("resource");
        assertEquals("VantagePoint", resource.getLabels().iterator().next().name());
        assertEquals("beta", resource.getProperty("alpha"));
    }

    @Test
    public void testIssue1714WithRestartDb() throws Exception {
        db.executeTransactionally("CREATE (i:Target {value: 2});");
        db.executeTransactionally("CALL apoc.custom.declareFunction('nn(val::INTEGER) :: NODE', 'MATCH (t:Target {value : $val}) RETURN t')");
        restartDb();
        TestUtil.testCall(db, "RETURN custom.nn(2) as row", (row) -> assertEquals(2L, ((Node) row.get("row")).getProperty("value")));
    }
}