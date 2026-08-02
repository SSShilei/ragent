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

package com.nageoffer.ai.ragent.rag.core.rewrite;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 精确实体短路检测器（决策树第一档）
 * <p>
 * query 含精确数字 / 型号 / 日期等强实体时，LLM 改写有概率把它泛化或改错（"P6 15000"
 * 可能变成"职级 6 工资 15000"），反而降低向量召回质量。命中即跳过 LLM 改写，
 * 走原 query（仅术语归一化）+ 规则拆分。
 * <p>
 * 纯规则、零成本，与 multi-query 等扩展正交：精确实体 query 本身语义自包含，
 * 不需要再生成语义变体，因此短路时连 maybeExpandVariants 也跳过。
 */
@Component
public class ExactEntityDetector {

    /**
     * 4 位以上纯数字（年份/工号/订单号/大额数值）
     * 例：2024、2083847186960904192、15000
     */
    private static final Pattern LONG_NUMBER = Pattern.compile("\\d{4,}");

    /**
     * 字母数字混合型号编码
     * 匹配两类：
     *   1) 带 -/_/ 分段（≥2 段且至少一段含数字）：A8-C3x、RAG-001、iPhone-15
     *   2) 字母与数字紧贴无分隔（前含字母后含数字）：iPhone15、P6、SKU2024
     * (?iu) 启用 Unicode + 不区分大小写，兼容小写开头的型号（iPhone、SKU 等）
     * 例：P6、A8-C3x、RAG-001、iPhone15ProMax
     */
    private static final Pattern ALPHANUMERIC_CODE = Pattern.compile(
            "(?iu)(?=.*\\d)[A-Z0-9]+(?:[-_/][A-Z0-9]+)+"
                    + "|(?=[A-Z]*\\d)[A-Z]+\\d+[A-Z0-9]*"
                    + "|\\d+[A-Z]+[A-Z0-9]*");

    /**
     * 日期：YYYY-MM-DD / YYYY/MM/DD / YYYY年MM月DD日 / YYYY.MM
     * 例：2024-06-30、2024/6/15、2024年6月、2024.07
     */
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}[-/.年]\\d{1,2}([月/-/.]\\d{1,2}日?)?");

    /**
     * 金额/百分比：含币种符号或单位 后跟数字
     * 例：$1500、5万元、15k、3.5M、20%
     */
    private static final Pattern AMOUNT = Pattern.compile("(?i)([$￥]\\s?\\d|\\d+\\s?(?:万元|k|m|%)|\\d+\\s?(?:元|块))");

    /**
     * 判断 query 是否含精确实体
     * <p>
     * 命中任一类别即返回 true。query 为空或纯文本不带实体返回 false
     *
     * @param query 原始 query（未归一化）
     * @return 是否含精确实体
     */
    public boolean hasExactEntity(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        return LONG_NUMBER.matcher(query).find()
                || ALPHANUMERIC_CODE.matcher(query).find()
                || ISO_DATE.matcher(query).find()
                || AMOUNT.matcher(query).find();
    }
}