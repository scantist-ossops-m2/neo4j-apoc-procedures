package apoc.vectordb;

import apoc.Extended;
import apoc.ml.RestAPIConfig;
import apoc.result.ListResult;
import apoc.result.MapResult;
import apoc.util.UrlResolver;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.security.URLAccessChecker;
import org.neo4j.internal.kernel.api.procs.ProcedureCallContext;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static apoc.ml.RestAPIConfig.METHOD_KEY;
import static apoc.util.Util.map;
import static apoc.vectordb.VectorDb.executeRequest;
import static apoc.vectordb.VectorDb.getEmbeddingResult;
import static apoc.vectordb.VectorDb.getEmbeddingResultStream;
import static apoc.vectordb.VectorDbHandler.Type.WEAVIATE;
import static apoc.vectordb.VectorDbUtil.*;

@Extended
public class Weaviate {

    @Context
    public ProcedureCallContext procedureCallContext;

    @Context
    public Transaction tx;

    @Context
    public GraphDatabaseService db;

    @Context
    public URLAccessChecker urlAccessChecker;

    @Procedure("apoc.vectordb.weaviate.createCollection")
    @Description("apoc.vectordb.weaviate.createCollection(hostOrKey, collection, similarity, size, $config)")
    public Stream<MapResult> createCollection(@Name("hostOrKey") String hostOrKey,
                                              @Name("collection") String collection,
                                              @Name("similarity") String similarity,
                                              @Name("size") Long size,
                                              @Name(value = "configuration", defaultValue = "{}") Map<String, Object> configuration) throws Exception {
        var config = getVectorDbInfo(hostOrKey, collection, configuration, "%s/schema");
        config.putIfAbsent(METHOD_KEY, "POST");

        Map<String, Object> additionalBodies = Map.of("class", collection,
                "vectorIndexConfig", Map.of("distance", similarity, "size", size)
        );

        RestAPIConfig restAPIConfig = new RestAPIConfig(config, Map.of(), additionalBodies);

        return executeRequest(restAPIConfig, urlAccessChecker)
                .map(v -> (Map<String,Object>) v)
                .map(MapResult::new);
    }

    @Procedure("apoc.vectordb.weaviate.deleteCollection")
    @Description("apoc.vectordb.weaviate.deleteCollection")
    public Stream<MapResult> deleteCollection(
            @Name("hostOrKey") String hostOrKey,
            @Name("collection") String collection,
            @Name(value = "configuration", defaultValue = "{}") Map<String, Object> configuration) throws Exception {
        var config = getVectorDbInfo(hostOrKey, collection, configuration, "%s/schema/%s");
        config.putIfAbsent(METHOD_KEY, "DELETE");

        RestAPIConfig restAPIConfig = new RestAPIConfig(config);
        return executeRequest(restAPIConfig, urlAccessChecker)
                .map(v -> (Map<String,Object>) v)
                .map(MapResult::new);
    }


    @Procedure("apoc.vectordb.weaviate.upsert")
    @Description("apoc.vectordb.weaviate.upsert")
    public Stream<MapResult> upsert(
            @Name("hostOrKey") String hostOrKey,
            @Name("collection") String collection,
            @Name("vectors") List<Map<String, Object>> vectors,
            @Name(value = "configuration", defaultValue = "{}") Map<String, Object> configuration) throws Exception {
        var config = getVectorDbInfo(hostOrKey, collection, configuration, "%s/objects");
        config.putIfAbsent(METHOD_KEY, "POST");

        Map<String, Object> body = new HashMap<>();
        body.put("class", collection);
        RestAPIConfig restAPIConfig = new RestAPIConfig(config, Map.of(), body);
        
        return vectors.stream()
                .flatMap(vector -> {
                    try {
                        Map<String, Object> configBody = new HashMap<>(restAPIConfig.getBody());
                        configBody.putAll(vector);
                        configBody.put("properties", vector.remove("metadata"));
                        restAPIConfig.setBody(configBody);
                        
                        Stream<Object> objectStream = executeRequest(restAPIConfig, urlAccessChecker);
                        return objectStream;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .map(v -> (Map<String, Object>) v)
                .map(MapResult::new);
    }

    @Procedure(value = "apoc.vectordb.weaviate.delete", mode = Mode.SCHEMA)
    @Description("apoc.vectordb.weaviate.delete()")
    public Stream<ListResult> delete(@Name("hostOrKey") String hostOrKey,
                                     @Name("collection") String collection,
                                     @Name("ids") List<Object> ids,
                                     @Name(value = "configuration", defaultValue = "{}") Map<String, Object> configuration) throws Exception {
        var config = getVectorDbInfo(hostOrKey, collection, configuration, "%s/schema");
        config.putIfAbsent(METHOD_KEY, "DELETE");

        RestAPIConfig restAPIConfig = new RestAPIConfig(config, map(), map());

        List<Object> objects = ids.stream()
                .peek(id -> {
                    String endpoint = "%s/objects/%s/%s".formatted(restAPIConfig.getBaseUrl(), collection, id);
                    restAPIConfig.setEndpoint(endpoint);
                    try {
                        executeRequest(restAPIConfig, urlAccessChecker);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .toList();
        return Stream.of(new ListResult(objects));
    }

    @Procedure(value = "apoc.vectordb.weaviate.get", mode = Mode.SCHEMA)
    @Description("apoc.vectordb.weaviate.get()")
    public Stream<EmbeddingResult> query(@Name("hostOrKey") String hostOrKey,
                                                      @Name("collection") String collection,
                                                      @Name("ids") List<Object> ids,
                                                      @Name(value = "configuration", defaultValue = "{}") Map<String, Object> configuration) throws Exception {
        Map<String, Object> config = getVectorDbInfo(hostOrKey, collection, configuration, "%s/schema");

        /**
         * TODO: we put method: null as a workaround, it should be "GET": https://weaviate.io/developers/weaviate/api/rest#tag/objects/get/objects/{className}/{id}
         * Since with `method: GET` the {@link apoc.util.Util#openUrlConnection(URL, Map)} has a `setChunkedStreamingMode`
         * that makes the request to respond with an error 405 Method Not Allowed 
         */
        config.putIfAbsent(METHOD_KEY, null);

        List<String> fields = procedureCallContext.outputFields().toList();
        VectorEmbeddingConfig conf = WEAVIATE.get().getEmbedding().fromGet(config, procedureCallContext, ids);
        boolean hasEmbedding = fields.contains("vector") && conf.isAllResults();
        boolean hasMetadata = fields.contains("metadata");
        VectorMappingConfig mapping = conf.getMapping();
        
        String suffix = hasEmbedding ? "?include=vector" : "";
        
        return ids.stream()
                .flatMap(id -> {
                    String endpoint = "%s/objects/%s/%s".formatted(conf.getApiConfig().getBaseUrl(), collection, id) + suffix;
                    conf.getApiConfig().setEndpoint(endpoint);
                    try {
                        return executeRequest(conf.getApiConfig(), urlAccessChecker)
                                .map(v -> (Map) v)
                                .map(m -> getEmbeddingResult(conf, tx, hasEmbedding, hasMetadata, mapping, m));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    @Procedure(value = "apoc.vectordb.weaviate.query", mode = Mode.SCHEMA)
    @Description("apoc.vectordb.weaviate.query()")
    public Stream<EmbeddingResult> query(@Name("hostOrKey") String hostOrKey,
                                                      @Name("collection") String collection,
                                                      @Name(value = "vector", defaultValue = "[]") List<Double> vector,
                                                      @Name(value = "filter", defaultValue = "null") Object filter,
                                                      @Name(value = "limit", defaultValue = "10") long limit,
                                                      @Name(value = "configuration", defaultValue = "{}") Map<String, Object> configuration) throws Exception {
        Map<String, Object> config = getVectorDbInfo(hostOrKey, collection, configuration, "%s/graphql");

        VectorEmbeddingConfig conf = WEAVIATE.get().getEmbedding().fromQuery(config, procedureCallContext, vector, filter, limit, collection);
        return getEmbeddingResultStream(conf, procedureCallContext, urlAccessChecker, tx, 
                v -> {
                    Object getValue = ((Map<String, Map>) v).get("data").get("Get");
                    Object collectionValue = ((Map) getValue).get(collection);
                    return ((List<Map>) collectionValue).stream()
                            .map(i -> {
                                Map additional = (Map) i.remove("_additional");

                                Map map = new HashMap<>();
                                map.put(conf.getMetadataKey(), i);
                                map.put(conf.getScoreKey(), additional.get("distance"));
                                map.put(conf.getIdKey(), additional.get("id"));
                                map.put(conf.getVectorKey(), additional.get("vector"));
                                return map;
                            });
                }
        );
    }

    private Map<String, Object> getVectorDbInfo(String hostOrKey, String collection, Map<String, Object> configuration, String templateUrl) {
        return getCommonVectorDbInfo(hostOrKey, collection, configuration, templateUrl, WEAVIATE.get());
    }

    protected String getWeaviateUrl(String hostOrKey) {
        String baseUrl = new UrlResolver("http", "localhost", 8000)
                .getUrl("weaviate", hostOrKey);
        return baseUrl + "/v1";
    }
}
