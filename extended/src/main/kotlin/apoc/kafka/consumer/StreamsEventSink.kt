package apoc.kafka.consumer

import apoc.kafka.consumer.kafka.KafkaEventSink
import org.neo4j.kernel.internal.GraphDatabaseAPI
import org.neo4j.logging.Log
import apoc.kafka.events.StreamsPluginStatus

object StreamsEventSinkFactory {
    fun getStreamsEventSink(config: Map<String, String>, //streamsQueryExecution: StreamsEventSinkQueryExecution,
                          /*  streamsTopicService: StreamsTopicService, */log: Log, db: GraphDatabaseAPI): KafkaEventSink {
        return KafkaEventSink(/*config, streamsQueryExecution, streamsTopicService, log, */db)
    }
}

open class StreamsEventSinkConfigMapper(private val streamsConfigMap: Map<String, String>, private val mappingKeys: Map<String, String>) {
    open fun convert(config: Map<String, String>): Map<String, String> {
        val props = streamsConfigMap
                .toMutableMap()
        props += config.mapKeys { mappingKeys.getOrDefault(it.key, it.key) }
        return props
    }
}