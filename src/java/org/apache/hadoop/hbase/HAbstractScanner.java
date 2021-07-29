/**
 * Copyright 2007 The Apache Software Foundation
 * <p>
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase;

import java.io.IOException;
import java.util.Iterator;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.Vector;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.io.Text;

/**
 * Abstract base class that implements the HScannerInterface.
 * Used by the concrete HMemcacheScanner and HStoreScanners
 */
public abstract class HAbstractScanner implements HInternalScannerInterface {
    final Log LOG = LogFactory.getLog(this.getClass().getName());

    // Pattern to determine if a column key is a regex
    static Pattern isRegexPattern =
            Pattern.compile("^.*[\\\\+|^&*$\\[\\]\\}{)(]+.*$");

    /**
     * The kind of match we are doing on a column:
     */
    private static enum MATCH_TYPE {
        /**
         * Just check the column family name
         */
        FAMILY_ONLY,
        /**
         * Column family + matches regex
         */
        REGEX,
        /**
         * Literal matching
         */
        SIMPLE
    }

    /**
     * This class provides column matching functions that are more sophisticated
     * than a simple string compare. There are three types of matching:
     * <ol>
     * <li>Match on the column family name only</li>
     * <li>Match on the column family + column key regex</li>
     * <li>Simple match: compare column family + column key literally</li>
     * </ul>
     */
    private static class ColumnMatcher {
        private boolean wildCardmatch;
        private MATCH_TYPE matchType;
        private Text family;
        private Pattern columnMatcher;
        private Text col;

        ColumnMatcher(final Text col) throws IOException {
            // zeng: column
            Text qualifier = HStoreKey.extractQualifier(col);

            try {
                if (qualifier == null || qualifier.getLength() == 0) {  // zeng: 只有family
                    this.matchType = MATCH_TYPE.FAMILY_ONLY;
                    this.family = HStoreKey.extractFamily(col).toText();
                    // zeng: 通配
                    this.wildCardmatch = true;
                } else if (isRegexPattern.matcher(qualifier.toString()).matches()) {    // zeng: 是正则
                    this.matchType = MATCH_TYPE.REGEX;
                    // zeng: 正则
                    this.columnMatcher = Pattern.compile(col.toString());
                    // zeng: 通配
                    this.wildCardmatch = true;
                } else {    // zeng: 匹配 family + column
                    this.matchType = MATCH_TYPE.SIMPLE;
                    this.col = col;
                    // zeng: 不是通配
                    this.wildCardmatch = false;
                }
            } catch (Exception e) {
                throw new IOException("Column: " + col + ": " + e.getMessage());
            }
        }

        /**
         * Matching method
         */
        boolean matches(Text c) throws IOException {
            if (this.matchType == MATCH_TYPE.SIMPLE) {
                return c.equals(this.col);
            } else if (this.matchType == MATCH_TYPE.FAMILY_ONLY) {
                return HStoreKey.extractFamily(c).equals(this.family);
            } else if (this.matchType == MATCH_TYPE.REGEX) {
                return this.columnMatcher.matcher(c.toString()).matches();
            } else {
                throw new IOException("Invalid match type: " + this.matchType);
            }
        }

        boolean isWildCardMatch() {
            return this.wildCardmatch;
        }
    }

    protected TreeMap<Text, Vector<ColumnMatcher>> okCols;        // Holds matchers for each column family

    protected boolean scannerClosed = false;                      // True when scanning is done

    // Keys retrieved from the sources
    protected HStoreKey keys[];
    // Values that correspond to those keys
    protected byte[][] vals;

    protected long timestamp;                                     // The timestamp to match entries against
    private boolean wildcardMatch;
    private boolean multipleMatchers;

    /**
     * Constructor for abstract base class
     */
    HAbstractScanner(long timestamp, Text[] targetCols) throws IOException {
        this.timestamp = timestamp;

        this.wildcardMatch = false;
        this.multipleMatchers = false;

        // zeng: 每个family下有一个 ColumnMatch 列表
        this.okCols = new TreeMap<Text, Vector<ColumnMatcher>>();

        for (int i = 0; i < targetCols.length; i++) {   // zeng: 每个column一个matcher
            Text family = HStoreKey.extractFamily(targetCols[i]).toText();

            Vector<ColumnMatcher> matchers = okCols.get(family);
            if (matchers == null) {
                matchers = new Vector<ColumnMatcher>();
            }

            // zeng: new ColumnMatcher
            ColumnMatcher matcher = new ColumnMatcher(targetCols[i]);
            if (matcher.isWildCardMatch()) {
                this.wildcardMatch = true;
            }
            matchers.add(matcher);

            if (matchers.size() > 1) {
                this.multipleMatchers = true;
            }

            okCols.put(family, matchers);
        }
    }

    /**
     * For a particular column i, find all the matchers defined for the column.
     * Compare the column family and column key using the matchers. The first one
     * that matches returns true. If no matchers are successful, return false.
     *
     * @param i index into the keys array
     * @return true  - if any of the matchers for the column match the column family
     * and the column key.
     * @throws IOException
     */
    boolean columnMatch(int i) throws IOException {
        // zeng: 找到的 full column
        Text column = keys[i].getColumn();

        // zeng: family下的 ColumnMatcher 列表
        Vector<ColumnMatcher> matchers = okCols.get(HStoreKey.extractFamily(column));

        if (matchers == null) {
            return false;
        }

        // zeng: 查找对应的matcher
        for (int m = 0; m < matchers.size(); m++) {
            if (matchers.get(m).matches(column)) {
                return true;
            }
        }

        return false;
    }

    /**
     * If the user didn't want to start scanning at the first row, this method
     * seeks to the requested row.
     */
    abstract boolean findFirstRow(int i, Text firstRow) throws IOException;

    /**
     * The concrete implementations provide a mechanism to find the next set of values
     */
    abstract boolean getNext(int i) throws IOException;

    /**
     * Mechanism used by concrete implementation to shut down a particular scanner
     */
    abstract void closeSubScanner(int i);

    /**
     * {@inheritDoc}
     */
    public boolean isWildcardScanner() {
        return this.wildcardMatch;
    }

    /**
     * {@inheritDoc}
     */
    public boolean isMultipleMatchScanner() {
        return this.multipleMatchers;
    }

    /**
     * Get the next set of values for this scanner.
     *
     * @param key     The key that matched
     * @param results All the results for <code>key</code>
     * @return true if a match was found
     * @throws IOException
     * @see org.apache.hadoop.hbase.HScannerInterface#next(org.apache.hadoop.hbase.HStoreKey, java.util.SortedMap)
     */
    public boolean next(HStoreKey key, SortedMap<Text, byte[]> results) throws IOException {

        if (scannerClosed) {
            return false;
        }

        // Find the next row label (and timestamp)
        Text chosenRow = null;
        long chosenTimestamp = -1;

        // zeng: keys中最小的一个
        for (int i = 0; i < keys.length; i++) {

            if (
                    keys[i] != null && columnMatch(i) && keys[i].getTimestamp() <= this.timestamp && (
                            chosenRow == null || keys[i].getRow().compareTo(chosenRow) < 0 || (
                                    keys[i].getRow().compareTo(chosenRow) == 0 && keys[i].getTimestamp() > chosenTimestamp
                            )
                    )
            ) {
                chosenRow = new Text(keys[i].getRow());
                // zeng: 一次只查找一个timestamp下的column
                chosenTimestamp = keys[i].getTimestamp();
            }
        }

        // Grab all the values that match this row/timestamp
        boolean insertedItem = false;
        if (chosenRow != null) {

            // zeng: 写入key中
            key.setRow(chosenRow);
            key.setVersion(chosenTimestamp);
            key.setColumn(new Text(""));

            for (int i = 0; i < keys.length; i++) { // zeng: 遍历所有key, 看有没有同一行的

                // Fetch the data
                while ((keys[i] != null) && keys[i].getRow().compareTo(chosenRow) == 0) {   // zeng: 同一个rowkey 的 key

                    // If we are doing a wild card match or there are multiple matchers
                    // per column, we need to scan all the older versions of this row
                    // to pick up the rest of the family members

                    // zeng: 如果不是通配 或者 有 多个 column matcher, 就表示只有一个column
                    // zeng: 只查找同一个timestamp下的column
                    if (!wildcardMatch && !multipleMatchers && keys[i].getTimestamp() != chosenTimestamp) {
                        break;
                    }

                    // zeng: 这个key 的 column 是 目标column
                    if (columnMatch(i)) {

                        // We only want the first result for any specific family member
                        if (!results.containsKey(keys[i].getColumn())) {
                            results.put(new Text(keys[i].getColumn()), vals[i]);
                            insertedItem = true;
                        }

                    }

                    // zeng: 下一个cell
                    if (!getNext(i)) {
                        closeSubScanner(i);
                    }

                }

                // Advance the current scanner beyond the chosen row, to
                // a valid timestamp, so we're ready next time.

                // zeng: 下一 `行-timestamp` 第一个匹配的位置
                while (
                        keys[i] != null && (
                                keys[i].getRow().compareTo(chosenRow) <= 0 || keys[i].getTimestamp() > this.timestamp || !columnMatch(i)
                        )
                ) {
                    // zeng: 下一个cell
                    getNext(i);
                }
            }

        }

        return insertedItem;
    }

    /**
     * {@inheritDoc}
     */
    public Iterator<Entry<HStoreKey, SortedMap<Text, byte[]>>> iterator() {
        throw new UnsupportedOperationException("Unimplemented serverside. " +
                "next(HStoreKey, StortedMap(...) is more efficient");
    }
}