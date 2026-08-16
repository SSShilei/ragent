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

package com.nageoffer.ai.ragent.rag.core.mcp;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * MCP 工具 Schema → OpenAI Function Calling tools 参数 转换器
 * <p>
 * 把 MCP Server 通过 tools/list 返回的工具定义（{@link Tool}）转换为 OpenAI 兼容协议
 * 的 tools 数组格式，供 Function Calling 决策层使用：
 * <pre>
 * MCP:  {name, description, inputSchema: {type, properties, required}}
 *   ↓
 * OpenAI: {type: "function", function: {name, description, parameters: {...}}}
 * </pre>
 * 二者都遵循 JSON Schema，约 95% 字段直接透传，仅处理 OpenAI 不支持的边缘关键字。
 */
@Slf4j
@Component
public class McpToolSchemaConverter {

    /** $ref 引用前缀候选集：覆盖 JSON Schema 常见的 definitions / defs / $defs 三种定义容器 */
    private static final List<String> REF_PREFIXES = List.of("#/definitions/", "#/$defs/", "#/defs/");

    /** Schema 递归清洗的最大深度，防止 $ref 循环引用或异常深嵌套导致栈溢出 */
    private static final int MAX_SCHEMA_DEPTH = 20;

    /**
     * 批量转换：MCP Tool 列表 → OpenAI tools 数组
     * <p>
     * 空列表与 null 元素安全：空输入返回空列表，null 工具元素被跳过
     *
     * @param tools MCP 工具定义列表
     * @return OpenAI tools 格式的 Map 列表
     */
    public List<Map<String, Object>> convertTools(List<Tool> tools) {
        if (CollUtil.isEmpty(tools)) {
            return List.of();
        }
        return tools.stream()
                .filter(Objects::nonNull)
                .map(this::convertOne)
                .toList();
    }

    /**
     * 单个工具转换：包装为 OpenAI 的 function 声明
     *
     * @param tool MCP 工具定义（name / description / inputSchema 均非空）
     * @return {@code {type: "function", function: {name, description, parameters}}}
     */
    public Map<String, Object> convertOne(Tool tool) {
        Objects.requireNonNull(tool, "tool must not be null");
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", tool.name());
        // OpenAI 要求 description 非空，MCP 未提供时退化为空串，避免请求被模型拒绝
        function.put("description", tool.description() == null ? "" : tool.description());
        function.put("parameters", convertSchema(tool.inputSchema()));

        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("type", "function");
        wrapper.put("function", function);
        return wrapper;
    }

    /**
     * 将 MCP JsonSchema 转换为 OpenAI parameters（JSON Schema 对象子集）
     */
    private Map<String, Object> convertSchema(JsonSchema schema) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        // OpenAI function calling 的 parameters 必须是 type=object
        parameters.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        if (schema != null && CollUtil.isNotEmpty(schema.properties())) {
            schema.properties().forEach((name, raw) -> {
                if (raw instanceof Map<?, ?> rawMap) {
                    properties.put(name, cleanProperty(castMap(rawMap), schema));
                }
            });
        }
        parameters.put("properties", properties);

        if (schema != null && CollUtil.isNotEmpty(schema.required())) {
            parameters.put("required", schema.required());
        }
        return parameters;
    }

    /**
     * 清洗单个属性定义：处理 $ref 内联解析、组合关键字降级、递归清洗嵌套结构
     */
    private Map<String, Object> cleanProperty(Map<String, Object> property, JsonSchema rootSchema) {
        return cleanProperty(property, rootSchema, 0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cleanProperty(Map<String, Object> property, JsonSchema rootSchema, int depth) {
        // 深度护栏：防止 $ref 循环引用或异常深嵌套导致栈溢出
        if (depth > MAX_SCHEMA_DEPTH) {
            log.warn("MCP tool schema depth exceeded, fallback to string type, depth={}", depth);
            return stringFallback();
        }

        // $ref 内联解析：从根 Schema 的定义容器中取出目标定义后递归清洗
        if (property.containsKey("$ref")) {
            Map<String, Object> resolved = resolveRef(String.valueOf(property.get("$ref")), rootSchema);
            if (resolved != null) {
                return cleanProperty(resolved, rootSchema, depth + 1);
            }
            log.warn("MCP tool schema $ref unresolved, fallback to string type, ref={}", property.get("$ref"));
            return stringFallback();
        }

        // oneOf/anyOf/allOf：OpenAI 不支持组合关键字，降级为宽松 string 保证请求可用
        if (property.containsKey("oneOf") || property.containsKey("anyOf") || property.containsKey("allOf")) {
            return stringFallback();
        }

        Map<String, Object> cleaned = new LinkedHashMap<>(property);
        // 剥离 OpenAI 不支持的 JSON Schema 关键字
        cleaned.remove("$schema");
        cleaned.remove("$ref");
        cleaned.remove("additionalProperties");
        cleaned.remove("definitions");
        cleaned.remove("defs");
        cleaned.remove("$defs");

        // 递归清洗嵌套对象属性
        if (cleaned.get("properties") instanceof Map) {
            Map<String, Object> nested = (Map<String, Object>) cleaned.get("properties");
            Map<String, Object> cleanedNested = new LinkedHashMap<>();
            nested.forEach((key, value) -> {
                if (value instanceof Map) {
                    cleanedNested.put(key, cleanProperty((Map<String, Object>) value, rootSchema, depth + 1));
                }
            });
            cleaned.put("properties", cleanedNested);
        }

        // 递归清洗数组元素
        if (cleaned.get("items") instanceof Map) {
            cleaned.put("items", cleanProperty((Map<String, Object>) cleaned.get("items"), rootSchema, depth + 1));
        }

        return cleaned;
    }

    /**
     * 降级兜底：无法映射到 OpenAI 子集时退回最宽松的 string 类型
     */
    private Map<String, Object> stringFallback() {
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("type", "string");
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    /**
     * 解析 $ref 引用：按 key 从根 Schema 的 definitions / defs 容器中取出目标定义
     */
    private Map<String, Object> resolveRef(String ref, JsonSchema rootSchema) {
        if (StrUtil.isBlank(ref) || rootSchema == null) {
            return null;
        }
        String key = ref;
        for (String prefix : REF_PREFIXES) {
            if (ref.startsWith(prefix)) {
                key = ref.substring(prefix.length());
                break;
            }
        }
        if (rootSchema.definitions() != null && rootSchema.definitions().get(key) instanceof Map<?, ?> defMap) {
            return castMap(defMap);
        }
        if (rootSchema.defs() != null && rootSchema.defs().get(key) instanceof Map<?, ?> defMap) {
            return castMap(defMap);
        }
        return null;
    }
}
