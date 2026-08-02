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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExactEntityDetectorTest {

    private final ExactEntityDetector detector = new ExactEntityDetector();

    @Test
    void longNumberHits() {
        assertTrue(detector.hasExactEntity("Bob 在 2024 年的薪资"));
        assertTrue(detector.hasExactEntity("订单号 2083847186960904192 状态"));
        assertTrue(detector.hasExactEntity("P6 基本工资 15000"));
    }

    @Test
    void alphanumericCodeHits() {
        assertTrue(detector.hasExactEntity("iPhone15 的续航"));
        assertTrue(detector.hasExactEntity("查询 RAG-001 的状态"));
        assertTrue(detector.hasExactEntity("A8-C3x 配置说明"));
    }

    @Test
    void isoDateHits() {
        assertTrue(detector.hasExactEntity("2024-06-30 上线的功能"));
        assertTrue(detector.hasExactEntity("2024年6月15日 出的 bug"));
        assertTrue(detector.hasExactEntity("2024/6/15 版本"));
    }

    @Test
    void amountHits() {
        assertTrue(detector.hasExactEntity("满 $1500 免运费"));
        assertTrue(detector.hasExactEntity("月销 5万元 的门店"));
        assertTrue(detector.hasExactEntity("占比 20% 的部分"));
    }

    @Test
    void plainTextNoHit() {
        assertFalse(detector.hasExactEntity("Block 体系是什么"));
        assertFalse(detector.hasExactEntity("RAG 流水线怎么做的"));
        assertFalse(detector.hasExactEntity("意图识别怎么实现的"));
    }

    @Test
    void shortNumberNotHit() {
        // 3 位以下纯数字不当精确数字（避免误伤"3 个步骤"这类）
        assertFalse(detector.hasExactEntity("3 个步骤"));
        assertFalse(detector.hasExactEntity("用 5 行实现"));
    }

    @Test
    void blankNoHit() {
        assertFalse(detector.hasExactEntity(null));
        assertFalse(detector.hasExactEntity(""));
        assertFalse(detector.hasExactEntity("   "));
    }
}