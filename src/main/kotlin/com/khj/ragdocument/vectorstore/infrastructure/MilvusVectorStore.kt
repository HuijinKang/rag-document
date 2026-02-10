package com.khj.ragdocument.vectorstore.infrastructure

import com.google.gson.JsonObject
import com.khj.ragdocument.vectorstore.domain.VectorSearchResult
import com.khj.ragdocument.vectorstore.domain.VectorStoreRepository
import io.milvus.v2.client.ConnectConfig
import io.milvus.v2.client.MilvusClientV2
import io.milvus.v2.common.DataType
import io.milvus.v2.common.IndexParam
import io.milvus.v2.service.collection.request.AddFieldReq
import io.milvus.v2.service.collection.request.CreateCollectionReq
import io.milvus.v2.service.collection.request.HasCollectionReq
import io.milvus.v2.service.vector.request.InsertReq
import io.milvus.v2.service.vector.request.SearchReq
import io.milvus.v2.service.vector.request.data.FloatVec
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class MilvusVectorStore(
    @Value("\${milvus.host}") private val host: String,
    @Value("\${milvus.port}") private val port: Int,
) : VectorStoreRepository {

    private val collectionName = "document_chunks"
    private val dimension = 1536

    private lateinit var client: MilvusClientV2

    @PostConstruct
    fun init() {
        client = MilvusClientV2(
            ConnectConfig.builder()
                .uri("http://$host:$port")
                .build()
        )

        val exists = client.hasCollection(
            HasCollectionReq.builder()
                .collectionName(collectionName)
                .build()
        )

        if (!exists) {
            createCollection()
        }
    }

    private fun createCollection() {
        val schema = CreateCollectionReq.CollectionSchema.builder()
            .enableDynamicField(false)
            .build()

        schema.addField(
            AddFieldReq.builder()
                .fieldName("chunk_id")
                .dataType(DataType.Int64)
                .isPrimaryKey(true)
                .autoID(false)
                .build()
        )

        schema.addField(
            AddFieldReq.builder()
                .fieldName("embedding")
                .dataType(DataType.FloatVector)
                .dimension(dimension)
                .build()
        )

        val indexParams = listOf(
            IndexParam.builder()
                .fieldName("embedding")
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.COSINE)
                .build()
        )

        client.createCollection(
            CreateCollectionReq.builder()
                .collectionName(collectionName)
                .collectionSchema(schema)
                .indexParams(indexParams)
                .build()
        )
    }

    override fun save(chunkId: Long, embedding: List<Float>) {
        saveBatch(mapOf(chunkId to embedding))
    }

    override fun saveBatch(chunkEmbeddings: Map<Long, List<Float>>) {
        val rows = chunkEmbeddings.map { (chunkId, embedding) ->
            JsonObject().apply {
                addProperty("chunk_id", chunkId)
                add("embedding", com.google.gson.Gson().toJsonTree(embedding))
            }
        }

        client.insert(
            InsertReq.builder()
                .collectionName(collectionName)
                .data(rows)
                .build()
        )
    }

    override fun search(queryEmbedding: List<Float>, topK: Int): List<VectorSearchResult> {
        val response = client.search(
            SearchReq.builder()
                .collectionName(collectionName)
                .data(listOf(FloatVec(queryEmbedding)))
                .topK(topK)
                .outputFields(listOf("chunk_id"))
                .build()
        )

        return response.searchResults.firstOrNull()?.map { result ->
            VectorSearchResult(
                chunkId = result.id as Long,
                score = result.score,
            )
        } ?: emptyList()
    }

    @PreDestroy
    fun close() {
        client.close()
    }
}
