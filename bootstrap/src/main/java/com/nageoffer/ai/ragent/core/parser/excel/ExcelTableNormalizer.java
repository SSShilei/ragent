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

package com.nageoffer.ai.ragent.core.parser.excel;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;

import java.util.ArrayList;
import java.util.List;

/**
 * Excel 表格规范化器
 * <p>
 * 把 POI Sheet 转为干净的 (headers, rows) 二维结构，处理：
 * <ul>
 *   <li>合并单元格展开：合并区域的左上角值复制到该区域每个 cell 位置（对 RAG 行级 chunk 自包含友好）</li>
 *   <li>多行表头展平：前 N 行合并成单行表头，列名用分隔符拼接（如 "财务|收入"）</li>
 *   <li>超链接保留：cell 文字外包 {@code [text](url)}</li>
 *   <li>公式回退：通过 {@link ExcelValueFormatter}</li>
 *   <li>全空行跳过</li>
 * </ul>
 */
public final class ExcelTableNormalizer {

    /**
     * 多行表头展平时使用的分隔符
     */
    public static final String HEADER_SEPARATOR = "|";

    private ExcelTableNormalizer() {
    }

    /**
     * 规范化结果
     *
     * @param headers      已展平的列名（长度等于 maxCol）
     * @param rows         数据行（与 headers 对齐，全空行已跳过）
     * @param caption      区域标题面包屑「文档标题 / section 标题」，供 contextual chunking 嵌入；无则为空串
     * @param lastRowIndex sheet 最后一行的索引（用于 Provenance.cellRange）
     * @param maxCol       列数
     */
    public record NormalizedTable(
            List<String> headers,
            List<List<String>> rows,
            String caption,
            int lastRowIndex,
            int maxCol
    ) {
        public boolean isEmpty() {
            return headers.isEmpty() && rows.isEmpty();
        }
    }

    /**
     * 规范化 sheet，按空行拆分为多个独立的表格区域
     *
     * @param sheet      POI sheet
     * @param formatter  DataFormatter 实例（线程不安全，调用方持有）
     * @param evaluator  公式求值器，可空
     * @param headerRows 表头占用的行数，{@code >= 1}
     * @return 规范化结果列表；空 sheet 返回空列表
     */
    public static List<NormalizedTable> normalize(Sheet sheet,
                                                  DataFormatter formatter,
                                                  FormulaEvaluator evaluator,
                                                  int headerRows) {
        if (headerRows < 1) {
            throw new IllegalArgumentException("headerRows must be >= 1, got " + headerRows);
        }

        int lastRowNum = sheet.getLastRowNum();
        if (lastRowNum < 0) {
            return List.of();
        }

        int maxCol = computeMaxColumns(sheet, lastRowNum);
        if (maxCol == 0) {
            return List.of();
        }

        // 步骤 1: 读取 sheet 到二维 grid（已应用 hyperlink wrap 与公式回退）
        String[][] grid = readGrid(sheet, lastRowNum, maxCol, formatter, evaluator);

        // 步骤 2: 展开合并单元格（grid 上原地填充）
        expandMergedRegions(grid, sheet.getMergedRegions(), lastRowNum, maxCol);

        // 步骤 3: 识别文档标题（sheet 第一行非空且为标题行，如「米加健康福利政策」），作为各区域 caption 的统一前缀
        int docTitleRow = findDocumentTitleRow(grid, lastRowNum, maxCol);
        String documentTitle = docTitleRow >= 0 ? firstNonEmpty(grid, docTitleRow, maxCol) : "";

        // 步骤 4: 按空行切分区域，每个区域独立提取表头与数据行
        List<NormalizedTable> tables = new ArrayList<>();
        int regionStart = -1;
        for (int r = 0; r <= lastRowNum; r++) {
            boolean empty = isEmptyRow(grid, r, maxCol);
            if (!empty && regionStart < 0) {
                regionStart = r;
            }
            if ((empty || r == lastRowNum) && regionStart >= 0) {
                int regionEnd = empty ? r - 1 : r;
                NormalizedTable table = buildRegion(grid, regionStart, regionEnd, headerRows, maxCol, documentTitle, docTitleRow);
                if (table != null && !table.isEmpty()) {
                    tables.add(table);
                }
                regionStart = -1;
            }
        }
        return tables;
    }

    /**
     * 从 grid 的一个行区间构建单个 NormalizedTable
     */
    private static NormalizedTable buildRegion(String[][] grid, int startRow, int endRow,
                                               int headerRows, int rawMaxCol,
                                               String documentTitle, int docTitleRow) {
        int effectiveCols = trimTrailingEmptyColumns(grid, startRow, endRow, rawMaxCol);
        if (effectiveCols == 0) {
            return new NormalizedTable(List.of(), List.of(), "", endRow, 0);
        }
        // 跳过前导标题行（文档标题 / section 标题）：合并单元格展开后整行同值，不应作为表头或数据行；
        // 标题文本提取为 caption「文档标题 / section 标题」，随每个 chunk 嵌入，保证可被检索到
        int titleRows = countLeadingTitleRows(grid, startRow, endRow, effectiveCols);
        List<String> sectionTitles = new ArrayList<>();
        for (int r = startRow; r < startRow + titleRows; r++) {
            if (r == docTitleRow) {
                continue;
            }
            sectionTitles.add(firstNonEmpty(grid, r, effectiveCols));
        }
        String caption = buildCaption(documentTitle, sectionTitles);

        int effectiveStartRow = startRow + titleRows;
        if (effectiveStartRow > endRow) {
            // 整个区域都是标题行，无表头与数据
            return new NormalizedTable(List.of(), List.of(), "", endRow, 0);
        }
        int regionRows = endRow - effectiveStartRow + 1;
        int effectiveHeaderRows = Math.min(headerRows, regionRows);
        // 折叠横向合并产生的重复列（某列在表头+数据全程与左邻列相同 → 视为重复，丢弃），避免「说明: X; 说明: X」
        int[] cols = collapseDuplicateColumns(grid, effectiveStartRow, endRow, effectiveCols);
        List<String> headers = flattenHeaders(grid, effectiveStartRow, effectiveHeaderRows, cols);
        int dataStartRow = effectiveStartRow + effectiveHeaderRows;
        List<List<String>> rows = dataStartRow <= endRow
                ? collectDataRows(grid, dataStartRow, endRow, cols)
                : List.of();
        return new NormalizedTable(headers, rows, caption, endRow, cols.length);
    }

    /**
     * 计算 sheet 内最大列数（跨所有行）
     */
    private static int computeMaxColumns(Sheet sheet, int lastRowNum) {
        int maxCol = 0;
        for (int r = 0; r <= lastRowNum; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            int lastCellNum = row.getLastCellNum();
            if (lastCellNum > maxCol) {
                maxCol = lastCellNum;
            }
        }
        return maxCol;
    }

    /**
     * 判断一行是否全空
     */
    private static boolean isEmptyRow(String[][] grid, int row, int maxCol) {
        for (int c = 0; c < maxCol; c++) {
            String v = grid[row][c];
            if (v != null && !v.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 从右向左裁剪全空列，返回有效列数（避免纯格式空 cell 撑大列宽）
     */
    private static int trimTrailingEmptyColumns(String[][] grid, int startRow, int endRow, int maxCol) {
        int effective = maxCol;
        while (effective > 0) {
            boolean allEmpty = true;
            for (int r = startRow; r <= endRow; r++) {
                String v = grid[r][effective - 1];
                if (v != null && !v.isEmpty()) {
                    allEmpty = false;
                    break;
                }
            }
            if (allEmpty) {
                effective--;
            } else {
                break;
            }
        }
        return effective;
    }

    /**
     * 折叠横向合并产生的重复列，返回保留的列索引数组
     * <p>
     * 横向合并（如 D3:E3 的「说明」）展开后会让相邻列在表头+数据全程取值相同，
     * 渲染成「说明: X; 说明: X」污染嵌入文本。此处从左到右扫描，丢弃与左邻列全程相同的列，
     * 连续多列相同时一并折叠到最左列。纵向填充（如租房补贴向下铺）不受影响
     */
    private static int[] collapseDuplicateColumns(String[][] grid, int headerRow, int endRow, int effectiveCols) {
        List<Integer> kept = new ArrayList<>(effectiveCols);
        for (int c = 0; c < effectiveCols; c++) {
            if (c > 0 && columnEqualsLeft(grid, headerRow, endRow, c)) {
                continue;
            }
            kept.add(c);
        }
        int[] cols = new int[kept.size()];
        for (int i = 0; i < cols.length; i++) {
            cols[i] = kept.get(i);
        }
        return cols;
    }

    /**
     * 判断列 {@code col} 在 {@code [headerRow, endRow]} 区间是否与左邻列取值完全相同（null 视作空串）
     */
    private static boolean columnEqualsLeft(String[][] grid, int headerRow, int endRow, int col) {
        for (int r = headerRow; r <= endRow; r++) {
            String cur = grid[r][col] == null ? "" : grid[r][col];
            String left = grid[r][col - 1] == null ? "" : grid[r][col - 1];
            if (!cur.equals(left)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 定位文档标题行：sheet 第一行非空行若是标题行（如「米加健康福利政策」）即返回其行号，否则返回 -1
     */
    private static int findDocumentTitleRow(String[][] grid, int lastRowNum, int maxCol) {
        for (int r = 0; r <= lastRowNum; r++) {
            if (isEmptyRow(grid, r, maxCol)) {
                continue;
            }
            return isTitleRow(grid, r, maxCol) ? r : -1;
        }
        return -1;
    }

    /**
     * 取一行第一个非空 cell 的值，无则返回空串
     */
    private static String firstNonEmpty(String[][] grid, int row, int maxCol) {
        for (int c = 0; c < maxCol; c++) {
            String v = grid[row][c];
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    /**
     * 拼接 caption 面包屑「文档标题 / section 标题」，空段跳过
     */
    private static String buildCaption(String documentTitle, List<String> sectionTitles) {
        StringBuilder sb = new StringBuilder();
        if (documentTitle != null && !documentTitle.isEmpty()) {
            sb.append(documentTitle);
        }
        for (String title : sectionTitles) {
            if (title == null || title.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(" / ");
            }
            sb.append(title);
        }
        return sb.toString();
    }

    /**
     * 统计从 startRow 开始的连续标题行数量，遇到第一个非标题行即停止
     */
    private static int countLeadingTitleRows(String[][] grid, int startRow, int endRow, int maxCol) {
        int count = 0;
        for (int r = startRow; r <= endRow; r++) {
            if (!isTitleRow(grid, r, maxCol)) {
                break;
            }
            count++;
        }
        return count;
    }

    /**
     * 判断一行是否为标题行：所有非空 cell 的值都相同且数量 {@code >= 2}
     * <p>
     * 文档标题、section 标题经合并单元格展开后，整行非空 cell 会是同一个值
     * （如 {@code [米加,米加,米加,米加]}）；真正的列名行则是多个不同值
     * （如 {@code [项目,适用范围,发放标准,说明]}），由此区分
     */
    private static boolean isTitleRow(String[][] grid, int row, int maxCol) {
        String first = null;
        int count = 0;
        for (int c = 0; c < maxCol; c++) {
            String v = grid[row][c];
            if (v == null || v.isEmpty()) {
                continue;
            }
            if (first == null) {
                first = v;
            } else if (!v.equals(first)) {
                return false;
            }
            count++;
        }
        return count >= 2;
    }

    /**
     * 读取 sheet 到二维数组，应用 hyperlink + 公式回退
     * <p>
     * 用 {@link Row.MissingCellPolicy#RETURN_NULL_AND_BLANK}：不存在的 cell 返回 null，
     * BLANK cell 仍返回 cell 实例（可携带 hyperlink）。这样空文字 + 超链接的场景能正确解析
     */
    private static String[][] readGrid(Sheet sheet, int lastRowNum, int maxCol,
                                       DataFormatter formatter, FormulaEvaluator evaluator) {
        String[][] grid = new String[lastRowNum + 1][maxCol];
        for (int r = 0; r <= lastRowNum; r++) {
            Row row = sheet.getRow(r);
            for (int c = 0; c < maxCol; c++) {
                String value = "";
                if (row != null) {
                    Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_NULL_AND_BLANK);
                    if (cell != null) {
                        String formatted = ExcelValueFormatter.format(cell, formatter, evaluator);
                        value = ExcelHyperlinkResolver.wrap(formatted, cell);
                    }
                }
                grid[r][c] = value;
            }
        }
        return grid;
    }

    /**
     * 把合并区域的左上角值复制到区域内所有 cell 位置
     */
    private static void expandMergedRegions(String[][] grid,
                                            List<CellRangeAddress> mergedRegions,
                                            int lastRowNum, int maxCol) {
        if (mergedRegions == null || mergedRegions.isEmpty()) {
            return;
        }
        for (CellRangeAddress region : mergedRegions) {
            int firstRow = region.getFirstRow();
            int firstCol = region.getFirstColumn();
            if (firstRow < 0 || firstRow > lastRowNum || firstCol < 0 || firstCol >= maxCol) {
                continue;
            }
            String value = grid[firstRow][firstCol];
            if (value == null || value.isEmpty()) {
                continue;
            }
            int rEnd = Math.min(region.getLastRow(), lastRowNum);
            int cEnd = Math.min(region.getLastColumn(), maxCol - 1);
            for (int r = firstRow; r <= rEnd; r++) {
                for (int c = firstCol; c <= cEnd; c++) {
                    grid[r][c] = value;
                }
            }
        }
    }

    /**
     * 展平前 N 行为单行表头：相邻相同值合并（避免合并单元格展开后的重复）
     */
    private static List<String> flattenHeaders(String[][] grid, int startRow, int headerRows, int[] cols) {
        List<String> headers = new ArrayList<>(cols.length);
        for (int c : cols) {
            StringBuilder sb = new StringBuilder();
            String prev = null;
            for (int r = startRow; r < startRow + headerRows; r++) {
                String v = grid[r][c];
                if (v == null || v.isEmpty()) {
                    continue;
                }
                if (v.equals(prev)) {
                    continue;
                }
                if (!sb.isEmpty()) {
                    sb.append(HEADER_SEPARATOR);
                }
                sb.append(v);
                prev = v;
            }
            headers.add(sb.toString());
        }
        return headers;
    }

    /**
     * 收集数据行（跳过全空）
     */
    private static List<List<String>> collectDataRows(String[][] grid, int startRow,
                                                      int endRow, int[] cols) {
        List<List<String>> rows = new ArrayList<>();
        for (int r = startRow; r <= endRow; r++) {
            List<String> rowValues = new ArrayList<>(cols.length);
            boolean allEmpty = true;
            for (int c : cols) {
                String v = grid[r][c];
                if (v != null && !v.isEmpty()) {
                    allEmpty = false;
                }
                rowValues.add(v == null ? "" : v);
            }
            if (!allEmpty) {
                rows.add(rowValues);
            }
        }
        return rows;
    }
}
