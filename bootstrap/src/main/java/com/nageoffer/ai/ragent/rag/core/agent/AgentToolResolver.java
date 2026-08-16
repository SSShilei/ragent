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

package com.nageoffer.ai.ragent.rag.core.agent;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolRegistry;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent 工具白名单解析器
 * <p>
 * 意图树在 Agent 循环中退居「范围收敛」角色：从已命中的 MCP 叶子节点提取 mcpToolId，
 * 仅把命中的工具喂给 LLM，而非全量暴露。这样在保留 LLM 自主决策的同时，
 * 缩小工具选择面，降低幻觉调用、Token 成本与安全面。
 */
@Component
@RequiredArgsConstructor
public class AgentToolResolver {

    private final McpToolRegistry toolRegistry;

    /**
     * 从子问题意图中解析命中的 MCP 工具白名单
     *
     * @param subIntents 意图解析结果
     * @return 命中的 MCP 工具定义列表（按注册表顺序，去重）
     */
    public List<Tool> resolveWhitelistTools(List<SubQuestionIntent> subIntents) {
        Set<String> toolIds = extractMcpToolIds(subIntents);
        if (toolIds.isEmpty()) {
            return List.of();
        }
        return toolRegistry.listAllTools().stream()
                .filter(tool -> toolIds.contains(tool.name()))
                .toList();
    }

    /**
     * 提取所有命中的 MCP 意图的 toolId（去空去重）
     */
    private Set<String> extractMcpToolIds(List<SubQuestionIntent> subIntents) {
        if (subIntents == null) {
            return Set.of();
        }
        return subIntents.stream()
                .flatMap(si -> si.nodeScores().stream())
                .filter(ns -> ns.getNode() != null && ns.getNode().isMCP())
                .map(ns -> ns.getNode().getMcpToolId())
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());
    }
}
