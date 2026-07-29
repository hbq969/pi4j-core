package com.github.hbq969.ai.pi.core.knowledge;

import com.github.hbq969.ai.pi.core.extension.RuntimeExtension;

import java.util.List;
import java.util.Map;

/**
 * 向量存储 SPI——定义向量数据库的增删查契约。
 * 每个实现（Chroma、Milvus、PgVector 等）只需实现此接口即可集成到 Agent 工具链。
 */
public interface VectorStore extends RuntimeExtension {

    // ==================== Collection 操作 ====================

    /** 创建或获取 collection，返回 collection ID。 */
    String getOrCreateCollection(String collectionName);

    /** 删除 collection。 */
    void deleteCollection(String collectionName);

    /** 列出所有 collection 名称。 */
    List<String> listCollections();

    // ==================== 向量操作 ====================

    /**
     * 批量添加向量及其元数据。
     *
     * @param collectionId collection ID
     * @param ids          文档片段 ID 列表
     * @param embeddings   向量列表（与 ids 一一对应）
     * @param metadatas    元数据列表
     * @param documents    原文列表
     */
    void add(String collectionId, List<String> ids, List<float[]> embeddings,
             List<Map<String, String>> metadatas, List<String> documents);

    /**
     * 按查询向量检索最相似的 topK 条结果。
     *
     * @param collectionId   collection ID
     * @param queryEmbedding 查询向量
     * @param topK           返回条数
     * @return 按相似度降序排列的结果列表
     */
    List<VectorQueryResult> query(String collectionId, float[] queryEmbedding, int topK);

    /**
     * 按元数据条件删除向量。
     *
     * @param collectionName collection 名称
     * @param where          元数据过滤条件
     * @return 删除的条数
     */
    int deleteByMetadata(String collectionName, Map<String, String> where);

    /**
     * 按元数据过滤拉取向量（用于重建索引等场景）。
     *
     * @param collectionId collection ID
     * @param where        元数据过滤条件（null 表示不过滤）
     * @param limit        最大返回条数
     * @param offset       偏移量
     * @return 结果列表
     */
    List<VectorQueryResult> getByMetadata(String collectionId, Map<String, String> where,
                                          int limit, int offset);

    /** 向量查询结果。 */
    record VectorQueryResult(String id, String document, Map<String, String> metadata, double score) {}
}
