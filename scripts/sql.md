-- ============================================
-- 1. 查看有哪些 collection 以及各自的 chunk 数量
-- ============================================
SELECT collection_name, COUNT(*) AS chunk_count
FROM t_knowledge_vector
GROUP BY collection_name
ORDER BY chunk_count DESC;


-- ============================================
-- 2. 查指定文档的所有 chunk（用你的 docId 替换）
-- ============================================
SELECT id, collection_name, vector_dims(embedding) AS dims,
LEFT(content, 80) AS content_preview
FROM t_knowledge_vector
WHERE id IN (
SELECT chunk_id FROM t_knowledge_chunk WHERE doc_id = '2083847186960904192'
)
ORDER BY id;


-- ============================================
-- 3. 验证向量维度是否正确（应该是 1536）
-- ============================================
SELECT id, vector_dims(embedding) AS dims
FROM t_knowledge_vector
LIMIT 5;


-- ============================================
-- 4. 手动插入一条测试向量
-- ============================================
INSERT INTO t_knowledge_vector (id, collection_name, content, metadata, embedding)
VALUES (
'1888888888888888888',
'1',
'这是一条测试文本',
'{"docId":"2083847186960904192","chunkIndex":99}'::jsonb,
'[0.01,0.02,0.03,...]'::vector   -- 替换为真实向量
);


-- ============================================
-- 5. 向量相似度检索（cosine 距离，越接近 1 越相似）
-- ============================================
SELECT id, collection_name,
1 - (embedding <=> '[0.01,0.02,...]'::vector) AS similarity,
LEFT(content, 100) AS content_preview
FROM t_knowledge_vector
WHERE collection_name = '1'
ORDER BY embedding <=> '[0.01,0.02,...]'::vector
LIMIT 5;


-- ============================================
-- 6. 删除指定文档的全部向量（重新入库前先清）
-- ============================================
DELETE FROM t_knowledge_vector
WHERE id IN (
SELECT chunk_id FROM t_knowledge_chunk WHERE doc_id = '2083847186960904192'
);
-- ============================================
-- 1. 查看有哪些 collection 以及各自的 chunk 数量
-- ============================================
SELECT collection_name, COUNT(*) AS chunk_count
FROM t_knowledge_vector
GROUP BY collection_name
ORDER BY chunk_count DESC;


-- ============================================
-- 2. 查指定文档的所有 chunk（用你的 docId 替换）
-- ============================================
SELECT id, collection_name, vector_dims(embedding) AS dims,
LEFT(content, 80) AS content_preview
FROM t_knowledge_vector
WHERE id IN (
SELECT chunk_id FROM t_knowledge_chunk WHERE doc_id = '2083847186960904192'
)
ORDER BY id;


-- ============================================
-- 3. 验证向量维度是否正确（应该是 1536）
-- ============================================
SELECT id, vector_dims(embedding) AS dims
FROM t_knowledge_vector
LIMIT 5;


-- ============================================
-- 4. 手动插入一条测试向量
-- ============================================
INSERT INTO t_knowledge_vector (id, collection_name, content, metadata, embedding)
VALUES (
'1888888888888888888',
'1',
'这是一条测试文本',
'{"docId":"2083847186960904192","chunkIndex":99}'::jsonb,
'[0.01,0.02,0.03,...]'::vector   -- 替换为真实向量
);


-- ============================================
-- 5. 向量相似度检索（cosine 距离，越接近 1 越相似）
-- ============================================
SELECT id, collection_name,
1 - (embedding <=> '[0.01,0.02,...]'::vector) AS similarity,
LEFT(content, 100) AS content_preview
FROM t_knowledge_vector
WHERE collection_name = '1'
ORDER BY embedding <=> '[0.01,0.02,...]'::vector
LIMIT 5;


-- ============================================
-- 6. 删除指定文档的全部向量（重新入库前先清）
-- ============================================
DELETE FROM t_knowledge_vector
WHERE id IN (
SELECT chunk_id FROM t_knowledge_chunk WHERE doc_id = '2083847186960904192'
);
