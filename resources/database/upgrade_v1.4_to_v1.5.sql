-- ragent v1.4 -> v1.5 升级脚本
-- 主题：向量库 metadata 增强 — 记录 embedding 模型与维度
--
-- 背景：
--   原 t_knowledge_vector.metadata 仅记录 doc_id / chunk_index，未记录嵌入模型与维度。
--   切换 Embedding 模型时无法定位旧模型建的 chunk，无法定向重建，存在"维度混库"风险
--   （新/旧维度向量混存导致相似度计算错乱）。
--
-- 改造方式：
--   不动 schema 列结构（避免影响存量数据），embedding 模型与维度由应用层写入 metadata JSONB。
--   PG 端通过 t_knowledge_vector.embedding 列类型 vector(1536) 已经硬约束维度，
--   无法写入异维向量；本脚本仅补 metadata 元数据，便于后续按 model/dim 过滤重建。
--
-- 不需要 DDL 变更（metadata 已是 JSONB），仅记录此次升级的标记与说明。

INSERT INTO t_biz_change_log (id, biz_type, biz_id, operation_type, action_desc, success)
SELECT '1888000000000000001', 'SCHEMA_UPGRADE', 'v1.4_to_v1.5', 'UPGRADE',
       '向量库 metadata 增强：新增 embedding_model / embedding_dim 字段（写入 metadata JSONB，不动 schema 列）',
       TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM t_biz_change_log WHERE biz_type = 'SCHEMA_UPGRADE' AND biz_id = 'v1.4_to_v1.5'
);

-- 兼容性说明：
-- 1. 存量 chunk 的 metadata 不含 embedding_model / embedding_dim，可视为"未知来源"
--    切换模型时这些 chunk 应作为"高危盲区"处理：建议直接全量重跑或保留旧 collection
-- 2. 新写入的 chunk 自带 embedding_model / embedding_dim，可 SQL filter 精确定位
-- 3. 排查维度混库：
--    SELECT metadata->>'embedding_model' AS model, (metadata->>'embedding_dim')::int AS dim, COUNT(*)
--    FROM t_knowledge_vector
--    GROUP BY model, dim;