/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.eval;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 评测检索接口出参（纯检索证据，无 LLM 输出）
 */
@Data
@Builder
public class EvalResponse {

    /**
     * 召回的业务文档 ID 列表（已去重；从 t_knowledge_document.doc_name 剥文件后缀得到，对齐评测集 reference_doc_ids）
     */
    private List<String> retrievedDocIds;

    /**
     * 召回的 chunk 主键列表（RetrievedChunk.id，已去重；用于调试）
     */
    private List<String> retrievedChunkIds;

    /**
     * 召回的 chunk 文本列表（与 retrievedChunkIds 顺序对应）
     */
    private List<String> retrievedContexts;

    /**
     * 与 retrievedContexts 一一对应的业务 docId 列表（chunk 维度、长度相同、保留 null、不去重）
     * 评测脚本计算 context_precision / context_recall 等 chunk 级指标时按 index 直接取用
     */
    private List<String> retrievedContextDocIds;

    /**
     * MCP 工具调用结果（无 MCP 分支时为空字符串）
     */
    private String mcpContext;

    /**
     * 是否走了 MCP 分支
     */
    private boolean hasMcp;

    /**
     * 是否走了 KB 检索
     */
    private boolean hasKb;

    /**
     * 子问题列表（改写拆分结果）
     */
    private List<String> subIntents;

    /**
     * 每个子问题 top-1 的意图叶子节点 id，与 subIntents 同序（无候选时为 null）
     * 评测脚本据此与评估集 intent_l2 比对，计算 Top-1 准确率
     */
    private List<String> intentLeafIds;

    /**
     * 总耗时（毫秒）
     */
    private long latencyMs;

    /**
     * 评测集标注的期望工具调用列表（Agent 评测用，如 ["weather_query"]）
     */
    private List<String> expectedToolCalls;

    /**
     * Function Calling 实际调用的工具名列表（按调用顺序，含重复）
     */
    private List<String> actualToolCalls;

    /**
     * Agent 循环实际执行的 LLM 调用轮数
     */
    private int toolCallRound;

    /**
     * Agent 循环累计 Token 估算（请求 + 响应）
     */
    private long totalTokens;
}
