package apoc.vectordb;

import apoc.Extended;
import apoc.ExtendedSystemPropertyKeys;
import apoc.ml.RestAPIConfig;
import apoc.result.ObjectResult;
import apoc.util.JsonUtil;
import apoc.util.Util;
import apoc.util.collection.Iterators;
import org.apache.commons.collections4.MapUtils;
import org.neo4j.graphdb.Entity;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.MultipleFoundException;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.security.URLAccessChecker;
import org.neo4j.internal.kernel.api.procs.ProcedureCallContext;
import org.neo4j.procedure.Admin;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static apoc.util.ExtendedUtil.setProperties;
import static apoc.util.JsonUtil.OBJECT_MAPPER;
import static apoc.util.SystemDbUtil.withSystemDb;
import static apoc.vectordb.VectorDbUtil.*;
import static apoc.vectordb.VectorEmbeddingConfig.ALL_RESULTS_KEY;
import static apoc.vectordb.VectorEmbeddingConfig.MAPPING_KEY;

/**
 * Base class
 */
@Extended
public class VectorDb {

    @Context
    public URLAccessChecker urlAccessChecker;
    
    @Context
    public GraphDatabaseService db;
    
    @Context
    public Transaction tx;
    
    @Context
    public ProcedureCallContext procedureCallContext;

    /**
     * We can use this procedure with every API that return something like this:
     * ```
     *   [
     *      "idKey": "idValue",
     *      "scoreKey": 1,
     *      "vectorKey": [ ]
     *      "metadataKey": { .. },
     *      "textKey": "..."
     *   ],
     *   [
     *      ...
     *   ]
     * ```
     * 
     * Otherwise, if the result is different (e.g. the Chroma result), we have to leverage the apoc.vectordb.custom,
     * which retrurn an Object, but we can't use it to filter result via `ProcedureCallContext procedureCallContext` 
     * and mapping data to auto-create neo4j vector indexes and properties
     */
    @Procedure(value = "apoc.vectordb.custom.get", mode = Mode.SCHEMA)
    @Description("apoc.vectordb.custom.get(host, $configuration) - Customizable get / query procedure")
    public Stream<EmbeddingResult> get(@Name("host") String host,
                                       @Name(value = "configuration", defaultValue = "{}") Map<String, Object> configuration) throws Exception {

        getEndpoint(configuration, host);
        VectorEmbeddingConfig restAPIConfig = new VectorEmbeddingConfig(configuration);
        return getEmbeddingResultStream(restAPIConfig, procedureCallContext, urlAccessChecker, tx);
    }

    public static Stream<EmbeddingResult> getEmbeddingResultStream(VectorEmbeddingConfig conf,
                                                                   ProcedureCallContext procedureCallContext,
                                                                   URLAccessChecker urlAccessChecker,
                                                                   Transaction tx) throws Exception {
        return getEmbeddingResultStream(conf, procedureCallContext, urlAccessChecker, tx, v -> ((List<Map>) v).stream());
    }
    
    public static Stream<EmbeddingResult> getEmbeddingResultStream(VectorEmbeddingConfig conf,
                                                                   ProcedureCallContext procedureCallContext,
                                                                   URLAccessChecker urlAccessChecker,
                                                                   Transaction tx,
                                                                   Function<Object, Stream<Map>> objectMapper) throws Exception {
        List<String> fields = procedureCallContext.outputFields().toList();

        boolean hasVector = fields.contains("vector") && conf.isAllResults();
        boolean hasMetadata = fields.contains("metadata");
        Stream<Object> resultStream = executeRequest(conf.getApiConfig(), urlAccessChecker);

        VectorMappingConfig mapping = conf.getMapping();

        return resultStream
                .flatMap(objectMapper)
                .map(m -> getEmbeddingResult(conf, tx, hasVector, hasMetadata, mapping, m));
    }

    public static EmbeddingResult getEmbeddingResult(VectorEmbeddingConfig conf, Transaction tx, boolean hasEmbedding, boolean hasMetadata, VectorMappingConfig mapping, Map m) {
        Object id = conf.isAllResults() ? m.get(conf.getIdKey()) : null;
        List<Double> embedding = hasEmbedding ? (List<Double>) m.get(conf.getVectorKey()) : null;
        Map<String, Object> metadata = hasMetadata ? (Map<String, Object>) m.get(conf.getMetadataKey()) : null;
        // in case of get operation, e.g. http://localhost:52798/collections/{coll_name}/points with Qdrant db,
        // score is not present
        Double score = Util.toDouble(m.get(conf.getScoreKey()));
        String text = conf.isAllResults() ? (String) m.get(conf.getTextKey()) : null;

        Entity entity = handleMapping(tx, mapping, metadata, embedding);
        if (entity != null) entity = Util.rebind(tx, entity);
        return new EmbeddingResult(id, score, embedding, metadata, text, 
                mapping.getLabel() == null ? null : (Node) entity,
                mapping.getLabel() != null ? null : (Relationship) entity
        );
    }

    private static Entity handleMapping(Transaction tx, VectorMappingConfig mapping, Map<String, Object> metadata, List<Double> embedding) {
        if (mapping.getProp() == null) {
            return null;
        }
        if (MapUtils.isEmpty(metadata)) {
            throw new RuntimeException("To use mapping config, the metadata should not be empty. Make sure you execute `YIELD metadata` on the procedure");
        }
        Map<String, Object> metaProps = new HashMap<>(metadata);
        if (mapping.getLabel() != null) {
            return handleMappingNode(tx, mapping, metaProps, embedding);
        } else if (mapping.getType() != null) {
            return handleMappingRel(tx, mapping, metaProps, embedding);
        } else {
            throw new RuntimeException("Mapping conf has to contain either label or type key");
        }
    }

    private static Entity handleMappingNode(Transaction transaction, VectorMappingConfig mapping, Map<String, Object> metaProps, List<Double> embedding) {
        try {
            Node node;
            Object propValue = metaProps.get(mapping.getId());
            node = transaction.findNode(Label.label(mapping.getLabel()), mapping.getProp(), propValue);
            if (node == null && mapping.isCreate()) {
                node = transaction.createNode(Label.label(mapping.getLabel()));
                node.setProperty(mapping.getProp(), propValue);
            }
            if (node != null) {
                setProperties(node, metaProps);
                setVectorProp(mapping, embedding, node);
            }

            return node;
        } catch (MultipleFoundException e) {
            throw new RuntimeException("Multiple nodes found");
        }
    }

    private static Entity handleMappingRel(Transaction transaction, VectorMappingConfig mapping, Map<String, Object> metaProps, List<Double> embedding) {
        try {
            // in this case we cannot auto-create the rel, since we should have to define start and end node as well
            Relationship rel;
            Object propValue = metaProps.get(mapping.getId());
            rel = transaction.findRelationship(RelationshipType.withName(mapping.getType()), mapping.getProp(), propValue);
            if (rel != null) {
                setProperties(rel, metaProps);
                setVectorProp(mapping, embedding, rel);
            }

            return rel;
        } catch (MultipleFoundException e) {
            throw new RuntimeException("Multiple relationships found");
        }
    }

    private static <T extends Entity> void setVectorProp(VectorMappingConfig mapping, List<Double> embedding, T entity) {
        if (mapping.getEmbeddingProp() == null) {
            return;
        }

        if (embedding == null) {
            String embeddingErrMsg = "The embedding value is null. Make sure you execute `YIELD embedding` on the procedure and you configured `%s: true`"
                    .formatted(ALL_RESULTS_KEY);
            throw new RuntimeException(embeddingErrMsg);
        }

        entity.setProperty(mapping.getEmbeddingProp(), embedding.stream()
                .map(Double::floatValue)
                .toArray(Float[]::new));
    }
    
    // TODO - evaluate. It could be renamed e.g. to `apoc.util.restapi.custom` or `apoc.restapi.custom`,
    //      since it can potentially be used as a generic method to call any RestAPI 
    @Procedure("apoc.vectordb.custom")
    @Description("apoc.vectordb.custom(host, $configuration) - fully customizable procedure, returns generic object results")
    public Stream<ObjectResult> custom(@Name("host") String host, @Name(value = "configuration", defaultValue = "{}") Map<String, Object> configuration) throws Exception {

        getEndpoint(configuration, host);
        RestAPIConfig restAPIConfig = new RestAPIConfig(configuration);
        return executeRequest(restAPIConfig, urlAccessChecker)
                .map(ObjectResult::new);
    }

    public static Stream<Object> executeRequest(RestAPIConfig apiConfig, URLAccessChecker urlAccessChecker) throws Exception {
        Map<String, Object> headers = apiConfig.getHeaders();
        Map<String, Object> configBody = apiConfig.getBody();
        String body = configBody == null
                ? null
                : OBJECT_MAPPER.writeValueAsString(configBody);
        
        String endpoint = apiConfig.getEndpoint();
        if (endpoint == null) {
            throw new RuntimeException("Endpoint must be specified");
        }

        return JsonUtil.loadJson(endpoint, headers, body, apiConfig.getJsonPath(), true, List.of(), urlAccessChecker);
    }

    @Admin
    @Procedure(name = "apoc.vectordb.store")
    @Description("CALL apoc.vectordb.store(vectorName, host, credentialsValue, mapping) - To store, given the vector defined by the 1st parameter, `host`, `credentials` and `mapping` into the system db")
    public void vectordb(@Name("vectorName") String vectorName, @Name("host") String host, @Name("credentialsValue") Object credentialsValue, @Name(value = "mapping", defaultValue = "{}") Map<String, Object> mapping) {
        VectorDbHandler.Type type = VectorDbHandler.Type.valueOf( vectorName.toUpperCase() );

        withSystemDb(transaction -> {
            Label label = Label.label(type.get().getLabel());
            Node node = Iterators.singleOrNull( transaction.findNodes(label) );
            if (node == null) {
                node = transaction.createNode(label);
            }

            node.setProperty(ExtendedSystemPropertyKeys.host.name(), host);
            
            if (credentialsValue != null) {
                node.setProperty( ExtendedSystemPropertyKeys.credentials.name(), Util.toJson(credentialsValue) );
            }

            // -- mapping props
            if (mapping != null) {
                node.setProperty( MAPPING_KEY, Util.toJson(mapping) );
            }
            return null;
        });

    }
}
