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

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class McpToolSchemaConverterTest {

    private final McpToolSchemaConverter converter = new McpToolSchemaConverter();

    @Test
    void convertOneProducesFunctionWrapper() {
        Tool tool = Tool.builder()
                .name("weather_query")
                .description("查询指定城市天气")
                .inputSchema(new JsonSchema("object", Map.of(), List.of(), null, null, null))
                .build();

        Map<String, Object> result = converter.convertOne(tool);

        assertEquals("function", result.get("type"));
        Map<String, Object> function = function(result);
        assertEquals("weather_query", function.get("name"));
        assertEquals("查询指定城市天气", function.get("description"));
        assertEquals("object", parameters(function).get("type"));
    }

    @Test
    void convertOneCarriesPropertiesAndRequired() {
        Map<String, Object> properties = Map.of(
                "city", Map.of("type", "string", "description", "城市名"),
                "days", Map.of("type", "integer")
        );
        Tool tool = Tool.builder()
                .name("weather_query")
                .description("查询天气")
                .inputSchema(new JsonSchema("object", properties, List.of("city"), null, null, null))
                .build();

        Map<String, Object> parameters = parameters(function(converter.convertOne(tool)));

        @SuppressWarnings("unchecked")
        Map<String, Object> resultProps = (Map<String, Object>) parameters.get("properties");
        assertEquals(2, resultProps.size());
        assertEquals(Map.of("type", "string", "description", "城市名"), resultProps.get("city"));
        assertEquals(List.of("city"), parameters.get("required"));
    }

    @Test
    void convertOneStripsUnsupportedKeywords() {
        Map<String, Object> properties = Map.of(
                "city", Map.of("type", "string", "$schema", "http://x", "additionalProperties", true)
        );
        Tool tool = Tool.builder()
                .name("t")
                .description("d")
                .inputSchema(new JsonSchema("object", properties, List.of(), null, null, null))
                .build();

        @SuppressWarnings("unchecked")
        Map<String, Object> resultProps = (Map<String, Object>) parameters(function(converter.convertOne(tool))).get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> city = (Map<String, Object>) resultProps.get("city");
        assertEquals("string", city.get("type"));
        assertFalse(city.containsKey("$schema"));
        assertFalse(city.containsKey("additionalProperties"));
    }

    @Test
    void convertOneDegradesOneOfToString() {
        Map<String, Object> properties = Map.of(
                "value", Map.of("oneOf", List.of(Map.of("type", "string"), Map.of("type", "integer")))
        );
        Tool tool = Tool.builder()
                .name("t")
                .description("d")
                .inputSchema(new JsonSchema("object", properties, List.of(), null, null, null))
                .build();

        @SuppressWarnings("unchecked")
        Map<String, Object> resultProps = (Map<String, Object>) parameters(function(converter.convertOne(tool))).get("properties");
        assertEquals(Map.of("type", "string"), resultProps.get("value"));
    }

    @Test
    void convertOneResolvesRef() {
        Map<String, Object> definitions = Map.of(
                "City", Map.of("type", "string", "description", "城市名")
        );
        Map<String, Object> properties = Map.of(
                "city", Map.of("$ref", "#/definitions/City")
        );
        Tool tool = Tool.builder()
                .name("t")
                .description("d")
                .inputSchema(new JsonSchema("object", properties, List.of(), null, null, definitions))
                .build();

        @SuppressWarnings("unchecked")
        Map<String, Object> resultProps = (Map<String, Object>) parameters(function(converter.convertOne(tool))).get("properties");
        assertEquals(Map.of("type", "string", "description", "城市名"), resultProps.get("city"));
    }

    @Test
    void convertOneRecursivelyCleansNestedPropertiesAndItems() {
        Map<String, Object> nested = Map.of(
                "type", "object",
                "properties", Map.of("inner", Map.of("type", "string"))
        );
        Map<String, Object> array = Map.of(
                "type", "array",
                "items", Map.of("type", "string")
        );
        Map<String, Object> properties = Map.of(
                "obj", nested,
                "list", array
        );
        Tool tool = Tool.builder()
                .name("t")
                .description("d")
                .inputSchema(new JsonSchema("object", properties, List.of(), null, null, null))
                .build();

        @SuppressWarnings("unchecked")
        Map<String, Object> resultProps = (Map<String, Object>) parameters(function(converter.convertOne(tool))).get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> obj = (Map<String, Object>) resultProps.get("obj");
        @SuppressWarnings("unchecked")
        Map<String, Object> innerProps = (Map<String, Object>) obj.get("properties");
        assertEquals(Map.of("type", "string"), innerProps.get("inner"));

        @SuppressWarnings("unchecked")
        Map<String, Object> list = (Map<String, Object>) resultProps.get("list");
        assertEquals(Map.of("type", "string"), list.get("items"));
    }

    @Test
    void convertToolsSkipsNullAndReturnsEmptyForBlank() {
        assertEquals(List.of(), converter.convertTools(null));
        assertEquals(List.of(), converter.convertTools(List.of()));

        Tool tool = Tool.builder()
                .name("t")
                .description("d")
                .inputSchema(new JsonSchema("object", Map.of(), List.of(), null, null, null))
                .build();
        // Arrays.asList 允许 null 元素，用于验证转换器对 null 工具的跳过逻辑
        List<Map<String, Object>> result = converter.convertTools(Arrays.asList(tool, null));
        assertEquals(1, result.size());
        assertEquals("t", function(result.get(0)).get("name"));
    }

    @Test
    void convertOneDegradesCyclicRefToString() {
        Map<String, Object> definitions = Map.of(
                "A", Map.of("$ref", "#/definitions/B"),
                "B", Map.of("$ref", "#/definitions/A")
        );
        Map<String, Object> properties = Map.of(
                "x", Map.of("$ref", "#/definitions/A")
        );
        Tool tool = Tool.builder()
                .name("t")
                .description("d")
                .inputSchema(new JsonSchema("object", properties, List.of(), null, null, definitions))
                .build();

        @SuppressWarnings("unchecked")
        Map<String, Object> resultProps = (Map<String, Object>) parameters(function(converter.convertOne(tool))).get("properties");
        // 循环 $ref 触发深度护栏，降级为 string 而非栈溢出
        assertEquals(Map.of("type", "string"), resultProps.get("x"));
    }

    @Test
    void convertOneHandlesNullDescriptionAndSchema() {
        Tool tool = Tool.builder().name("t").description(null).inputSchema(null).build();

        Map<String, Object> result = converter.convertOne(tool);
        assertEquals("", function(result).get("description"));
        assertEquals("object", parameters(function(result)).get("type"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> function(Map<String, Object> wrapper) {
        return (Map<String, Object>) wrapper.get("function");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parameters(Map<String, Object> function) {
        return (Map<String, Object>) function.get("parameters");
    }
}
