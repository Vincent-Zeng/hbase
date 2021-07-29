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
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.filter.RowFilterInterface;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.io.TextSequence;
import org.apache.hadoop.io.MapFile;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.util.StringUtils;
import org.onelab.filter.BloomFilter;
import org.onelab.filter.CountingBloomFilter;
import org.onelab.filter.Filter;
import org.onelab.filter.RetouchedBloomFilter;

/**
 * HStore maintains a bunch of data files.  It is responsible for maintaining
 * the memory/file hierarchy and for periodic flushes to disk and compacting
 * edits to the file.
 * <p>
 * Locking and transactions are handled at a higher level.  This API should not
 * be called directly by any writer, but rather by an HRegion manager.
 */
public class HStore implements HConstants {
    static final Log LOG = LogFactory.getLog(HStore.class);

    /**
     * The Memcache holds in-memory modifications to the HRegion.  This is really a
     * wrapper around a TreeMap that helps us when staging the Memcache out to disk.
     */
    static class Memcache {

        // Note that since these structures are always accessed with a lock held,
        // no additional synchronization is required.

        @SuppressWarnings("hiding")
        private final SortedMap<HStoreKey, byte[]> memcache =
                Collections.synchronizedSortedMap(new TreeMap<HStoreKey, byte[]>());

        volatile SortedMap<HStoreKey, byte[]> snapshot;

        @SuppressWarnings("hiding")
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        /**
         * Constructor
         */
        Memcache() {
            snapshot =
                    Collections.synchronizedSortedMap(new TreeMap<HStoreKey, byte[]>());
        }

        /**
         * Creates a snapshot of the current Memcache
         */
        void snapshot() {
            this.lock.writeLock().lock();
            try {
                synchronized (memcache) {
                    if (memcache.size() != 0) {
                        // zeng: 复制进snapshot这个sortedmap
                        snapshot.putAll(memcache);

                        // zeng: 清空memcache
                        memcache.clear();
                    }
                }
            } finally {
                this.lock.writeLock().unlock();
            }
        }

        /**
         * @return memcache snapshot
         */
        SortedMap<HStoreKey, byte[]> getSnapshot() {
            this.lock.writeLock().lock();
            try {
                SortedMap<HStoreKey, byte[]> currentSnapshot = snapshot;

                // zeng: reset snapshot
                snapshot = Collections.synchronizedSortedMap(new TreeMap<HStoreKey, byte[]>());

                return currentSnapshot;
            } finally {
                this.lock.writeLock().unlock();
            }
        }

        /**
         * Store a value.
         *
         * @param key
         * @param value
         */
        void add(final HStoreKey key, final byte[] value) {
            this.lock.readLock().lock();
            try {
                memcache.put(key, value);

            } finally {
                this.lock.readLock().unlock();
            }
        }

        /**
         * Look back through all the backlog TreeMaps to find the target.
         *
         * @param key
         * @param numVersions
         * @return An array of byte arrays ordered by timestamp.
         */
        List<byte[]> get(final HStoreKey key, final int numVersions) {
            this.lock.readLock().lock();
            try {
                List<byte[]> results;

                synchronized (memcache) {
                    // zeng: 从memcache中获取cell
                    results = internalGet(memcache, key, numVersions);
                }

                synchronized (snapshot) {
                    // zeng: 因为snapshot的时候memcache清空了, 所以还要取sanpshot
                    results.addAll(results.size(), internalGet(snapshot, key, numVersions - results.size()));
                }

                return results;

            } finally {
                this.lock.readLock().unlock();
            }
        }

        /**
         * Return all the available columns for the given key.  The key indicates a
         * row and timestamp, but not a column name.
         * <p>
         * The returned object should map column names to byte arrays (byte[]).
         *
         * @param key
         * @param results
         */
        void getFull(HStoreKey key, Map<Text, Long> deletes, SortedMap<Text, byte[]> results) {

            this.lock.readLock().lock();
            try {
                // zeng: memcache
                synchronized (memcache) {
                    internalGetFull(memcache, key, deletes, results);
                }

                // zeng: snapshot
                synchronized (snapshot) {
                    internalGetFull(snapshot, key, deletes, results);
                }

            } finally {
                this.lock.readLock().unlock();
            }
        }

        private void internalGetFull(
                SortedMap<HStoreKey, byte[]> map, HStoreKey key,
                Map<Text, Long> deletes, SortedMap<Text, byte[]> results
        ) {

            if (map.isEmpty() || key == null) {
                return;
            }

            // zeng: 大于等于key的cell
            SortedMap<HStoreKey, byte[]> tailMap = map.tailMap(key);

            for (Map.Entry<HStoreKey, byte[]> es : tailMap.entrySet()) {
                // zeng: key
                HStoreKey itKey = es.getKey();
                Text itCol = itKey.getColumn();

                if (results.get(itCol) == null && key.matchesWithoutColumn(itKey)) {    // zeng: 每一列只获取一个版本 . 同一个行timestamp小于传参的timestamp
                    // zeng: value
                    byte[] val = tailMap.get(itKey);

                    if (HLogEdit.isDeleted(val)) { // zeng: value不为HBASE::DELETEVAL
                        // zeng:deletes里存储column 值为 delete type 的 最新版本
                        if (!deletes.containsKey(itCol) || deletes.get(itCol).longValue() < itKey.getTimestamp()) {
                            deletes.put(new Text(itCol), itKey.getTimestamp());
                        }
                    } else if (!(deletes.containsKey(itCol) && deletes.get(itCol).longValue() >= itKey.getTimestamp())) {   // zeng: 如果 cell timestamp 比 delete type cell 的timestamp 大
                        results.put(new Text(itCol), val);
                    }
                } else if (key.getRow().compareTo(itKey.getRow()) < 0) {
                    break;
                }
            }
        }

        /**
         * Find the key that matches <i>row</i> exactly, or the one that immediately
         * preceeds it.
         */
        void getRowKeyAtOrBefore(final Text row,
                                 SortedMap<HStoreKey, Long> candidateKeys) {
            this.lock.readLock().lock();

            try {
                // zeng: memcache
                synchronized (memcache) {
                    internalGetRowKeyAtOrBefore(memcache, row, candidateKeys);
                }

                // zeng: snapshot
                synchronized (snapshot) {
                    internalGetRowKeyAtOrBefore(snapshot, row, candidateKeys);
                }
            } finally {
                this.lock.readLock().unlock();
            }
        }

        private void internalGetRowKeyAtOrBefore(
                SortedMap<HStoreKey, byte[]> map,
                Text key, SortedMap<HStoreKey, Long> candidateKeys
        ) {

            HStoreKey strippedKey = null;

            // we want the earliest possible to start searching from
            // zeng: 从哪个key开始搜索
            HStoreKey search_key = candidateKeys.isEmpty() ? new HStoreKey(key) : new HStoreKey(candidateKeys.firstKey().getRow());

            Iterator<HStoreKey> key_iterator = null;
            HStoreKey found_key = null;

            // zeng: 大于等于search_key的cell
            // get all the entries that come equal or after our search key
            SortedMap<HStoreKey, byte[]> tailMap = map.tailMap(search_key);

            // if there are items in the tail map, there's either a direct match to
            // the search key, or a range of values between the first candidate key
            // and the ultimate search key (or the end of the cache)
            if (!tailMap.isEmpty() && tailMap.firstKey().getRow().compareTo(key) <= 0) {    // zeng: tailMap中最小的key 小于等于 参数key
                key_iterator = tailMap.keySet().iterator();

                // keep looking at cells as long as they are no greater than the
                // ultimate search key and there's still records left in the map.
                do {
                    found_key = key_iterator.next();

                    if (found_key.getRow().compareTo(key) <= 0) {
                        strippedKey = stripTimestamp(found_key);

                        if (HLogEdit.isDeleted(tailMap.get(found_key))) {
                            if (candidateKeys.containsKey(strippedKey)) {
                                long bestCandidateTs = candidateKeys.get(strippedKey).longValue();

                                // zeng: 更大的timestamp
                                if (bestCandidateTs <= found_key.getTimestamp()) {
                                    // zeng: 从candidateKeys中移除
                                    candidateKeys.remove(strippedKey);
                                }
                            }
                        } else {
                            // zeng: 更大的key 或者 更大的timestamp
                            candidateKeys.put(strippedKey, new Long(found_key.getTimestamp()));
                        }
                    }

                } while (found_key.getRow().compareTo(key) <= 0 && key_iterator.hasNext()); // zeng: 下一个cell, 直到大于查找的row

            } else {
                // the tail didn't contain any keys that matched our criteria, or was
                // empty. examine all the keys that preceed our splitting point.
                // zeng: 小于search_key的cell
                SortedMap<HStoreKey, byte[]> headMap = map.headMap(search_key);

                // if we tried to create a headMap and got an empty map, then there are
                // no keys at or before the search key, so we're done.
                if (headMap.isEmpty()) {
                    return;
                }

                // if there aren't any candidate keys at this point, we need to search
                // backwards until we find at least one candidate or run out of headMap.
                if (candidateKeys.isEmpty()) {
                    HStoreKey[] cells = headMap.keySet().toArray(new HStoreKey[headMap.keySet().size()]);

                    Text lastRowFound = null;
                    // zeng: headMap中最大的key
                    for (int i = cells.length - 1; i >= 0; i--) {
                        HStoreKey thisKey = cells[i];

                        // if the last row we found a candidate key for is different than
                        // the row of the current candidate, we can stop looking.
                        if (lastRowFound != null && !lastRowFound.equals(thisKey.getRow())) {
                            break;
                        }

                        // if this isn't a delete, record it as a candidate key. also
                        // take note of the row of this candidate so that we'll know when
                        // we cross the row boundary into the previous row.
                        if (!HLogEdit.isDeleted(headMap.get(thisKey))) {
                            lastRowFound = thisKey.getRow();
                            candidateKeys.put(stripTimestamp(thisKey), new Long(thisKey.getTimestamp()));
                        }
                    }

                } else {
                    // if there are already some candidate keys, we only need to consider
                    // the very last row's worth of keys in the headMap, because any
                    // smaller acceptable candidate keys would have caused us to start
                    // our search earlier in the list, and we wouldn't be searching here.
                    // zeng: 小于search_key的最近一行 . TODO 有什么必要吗?
                    SortedMap<HStoreKey, byte[]> thisRowTailMap = headMap.tailMap(new HStoreKey(headMap.lastKey().getRow()));

                    key_iterator = thisRowTailMap.keySet().iterator();

                    do {
                        found_key = key_iterator.next();

                        if (HLogEdit.isDeleted(thisRowTailMap.get(found_key))) {
                            strippedKey = stripTimestamp(found_key);
                            if (candidateKeys.containsKey(strippedKey)) {
                                long bestCandidateTs = candidateKeys.get(strippedKey).longValue();
                                if (bestCandidateTs <= found_key.getTimestamp()) {
                                    candidateKeys.remove(strippedKey);
                                }
                            }
                        } else {
                            candidateKeys.put(stripTimestamp(found_key), found_key.getTimestamp());
                        }
                    } while (key_iterator.hasNext());   // zeng: 下一个cell
                }
            }
        }

        /**
         * Examine a single map for the desired key.
         * <p>
         * TODO - This is kinda slow.  We need a data structure that allows for
         * proximity-searches, not just precise-matches.
         *
         * @param map
         * @param key
         * @param numVersions
         * @return Ordered list of items found in passed <code>map</code>.  If no
         * matching values, returns an empty list (does not return null).
         */
        private ArrayList<byte[]> internalGet(
                final SortedMap<HStoreKey, byte[]> map, final HStoreKey key,
                final int numVersions
        ) {

            ArrayList<byte[]> result = new ArrayList<byte[]>();
            // TODO: If get is of a particular version -- numVersions == 1 -- we
            // should be able to avoid all of the tailmap creations and iterations
            // below.
            SortedMap<HStoreKey, byte[]> tailMap = map.tailMap(key);

            for (Map.Entry<HStoreKey, byte[]> es : tailMap.entrySet()) {
                HStoreKey itKey = es.getKey();

                if (itKey.matchesRowCol(key)) { // zeng: row column 相同
                    if (!HLogEdit.isDeleted(es.getValue())) {   // zeng: 如果值不是 HBASE::DELETEVAL
                        // zeng: 加入result
                        result.add(tailMap.get(itKey));
                    }
                }

                if (numVersions > 0 && result.size() >= numVersions) {  // zeng: numVersions个版本
                    break;
                }
            }

            return result;
        }

        /**
         * Get <code>versions</code> keys matching the origin key's
         * row/column/timestamp and those of an older vintage
         * Default access so can be accessed out of {@link HRegionServer}.
         *
         * @param origin   Where to start searching.
         * @param versions How many versions to return. Pass
         *                 {@link HConstants.ALL_VERSIONS} to retrieve all.
         * @return Ordered list of <code>versions</code> keys going from newest back.
         * @throws IOException
         */
        List<HStoreKey> getKeys(final HStoreKey origin, final int versions) {
            this.lock.readLock().lock();
            try {
                List<HStoreKey> results;
                synchronized (memcache) {
                    results = internalGetKeys(this.memcache, origin, versions);
                }
                synchronized (snapshot) {
                    results.addAll(results.size(), internalGetKeys(snapshot, origin,
                            versions == HConstants.ALL_VERSIONS ? versions :
                                    (versions - results.size())));
                }
                return results;

            } finally {
                this.lock.readLock().unlock();
            }
        }

        /**
         * @param origin   Where to start searching.
         * @param versions How many versions to return. Pass
         *                 {@link HConstants.ALL_VERSIONS} to retrieve all.
         * @return List of all keys that are of the same row and column and of
         * equal or older timestamp.  If no keys, returns an empty List. Does not
         * return null.
         */
        private List<HStoreKey> internalGetKeys(
                final SortedMap<HStoreKey, byte[]> map,
                final HStoreKey origin, final int versions
        ) {

            List<HStoreKey> result = new ArrayList<HStoreKey>();
            SortedMap<HStoreKey, byte[]> tailMap = map.tailMap(origin);
            for (Map.Entry<HStoreKey, byte[]> es : tailMap.entrySet()) {
                HStoreKey key = es.getKey();

                // if there's no column name, then compare rows and timestamps
                if (origin.getColumn().toString().equals("")) {
                    // if the current and origin row don't match, then we can jump
                    // out of the loop entirely.
                    if (!key.getRow().equals(origin.getRow())) {
                        break;
                    }
                    // if the rows match but the timestamp is newer, skip it so we can
                    // get to the ones we actually want.
                    if (key.getTimestamp() > origin.getTimestamp()) {
                        continue;
                    }
                } else { // compare rows and columns
                    // if the key doesn't match the row and column, then we're done, since
                    // all the cells are ordered.
                    if (!key.matchesRowCol(origin)) {
                        break;
                    }
                }

                if (!HLogEdit.isDeleted(es.getValue())) {
                    result.add(key);
                    if (versions != HConstants.ALL_VERSIONS && result.size() >= versions) {
                        // We have enough results.  Return.
                        break;
                    }
                }
            }
            return result;
        }

        // zeng: TODO

        /**
         * @param key
         * @return True if an entry and its content is {@link HGlobals.deleteBytes}.
         * Use checking values in store. On occasion the memcache has the fact that
         * the cell has been deleted.
         */
        boolean isDeleted(final HStoreKey key) {
            return HLogEdit.isDeleted(this.memcache.get(key));
        }

        /**
         * @return a scanner over the keys in the Memcache
         */
        HInternalScannerInterface getScanner(long timestamp, Text targetCols[], Text firstRow) throws IOException {

            // Here we rely on ReentrantReadWriteLock's ability to acquire multiple
            // locks by the same thread and to be able to downgrade a write lock to
            // a read lock. We need to hold a lock throughout this method, but only
            // need the write lock while creating the memcache snapshot

            this.lock.writeLock().lock(); // hold write lock during memcache snapshot

            // zeng: snapshot
            snapshot();                       // snapshot memcache

            this.lock.readLock().lock();      // acquire read lock
            this.lock.writeLock().unlock();   // downgrade to read lock
            try {
                // Prevent a cache flush while we are constructing the scanner

                // zeng: new MemcacheScanner
                return new MemcacheScanner(timestamp, targetCols, firstRow);

            } finally {
                this.lock.readLock().unlock();
            }
        }

        //////////////////////////////////////////////////////////////////////////////
        // MemcacheScanner implements the HScannerInterface.
        // It lets the caller scan the contents of the Memcache.
        //////////////////////////////////////////////////////////////////////////////

        class MemcacheScanner extends HAbstractScanner {
            SortedMap<HStoreKey, byte[]> backingMap;
            Iterator<HStoreKey> keyIterator;

            @SuppressWarnings("unchecked")
            MemcacheScanner(final long timestamp, final Text targetCols[], final Text firstRow) throws IOException {
                // zeng: 每个family下有一个 ColumnMatch 列表
                super(timestamp, targetCols);

                try {
                    // zeng: 复制snapshot
                    this.backingMap = new TreeMap<HStoreKey, byte[]>();
                    this.backingMap.putAll(snapshot);

                    this.keys = new HStoreKey[1];
                    this.vals = new byte[1][];

                    // Generate list of iterators

                    // zeng: 大于等于firstRow的所有的key
                    HStoreKey firstKey = new HStoreKey(firstRow);
                    if (firstRow != null && firstRow.getLength() != 0) {
                        keyIterator = backingMap.tailMap(firstKey).keySet().iterator();
                    } else {
                        keyIterator = backingMap.keySet().iterator();
                    }

                    while (getNext(0)) {    // zeng: 下一个cell
                        // zeng: 直到找到的key比firstrow大
                        if (!findFirstRow(0, firstRow)) {
                            continue;
                        }

                        // zeng: 直到找到对应的full column
                        if (columnMatch(0)) {
                            break;
                        }
                    }

                } catch (RuntimeException ex) {
                    LOG.error("error initializing Memcache scanner: ", ex);
                    close();
                    IOException e = new IOException("error initializing Memcache scanner");
                    e.initCause(ex);
                    throw e;

                } catch (IOException ex) {
                    LOG.error("error initializing Memcache scanner: ", ex);
                    close();
                    throw ex;
                }
            }

            /**
             * The user didn't want to start scanning at the first row. This method
             * seeks to the requested row.
             *
             * @param i        which iterator to advance
             * @param firstRow seek to this row
             * @return true if this is the first row
             */
            @Override
            boolean findFirstRow(int i, Text firstRow) {
                // zeng: 找到的 key 是否比 参数key 大
                return firstRow.getLength() == 0 || keys[i].getRow().compareTo(firstRow) >= 0;
            }

            /**
             * Get the next value from the specified iterator.
             *
             * @param i Which iterator to fetch next value from
             * @return true if there is more data available
             */
            @Override
            boolean getNext(int i) {
                boolean result = false;
                while (true) {
                    if (!keyIterator.hasNext()) {
                        closeSubScanner(i);
                        break;
                    }

                    // Check key is < than passed timestamp for this scanner.
                    // zeng: 下一个key
                    HStoreKey hsk = keyIterator.next();

                    if (hsk == null) {
                        throw new NullPointerException("Unexpected null key");
                    }

                    if (hsk.getTimestamp() <= this.timestamp) { // zeng: timestamp 需要小于等于 参数timestamp
                        // zeng:  memcache 和 hfile 的scanner 各自占用 keys 和 vals 中一个位置

                        // zeng: key
                        this.keys[i] = hsk;
                        // zeng: value
                        this.vals[i] = backingMap.get(keys[i]);

                        result = true;

                        break;
                    }
                }

                return result;
            }

            /**
             * Shut down an individual map iterator.
             */
            @Override
            void closeSubScanner(int i) {
                keyIterator = null;
                keys[i] = null;
                vals[i] = null;
                backingMap = null;
            }

            /**
             * Shut down map iterators
             */
            public void close() {
                if (!scannerClosed) {
                    if (keyIterator != null) {
                        closeSubScanner(0);
                    }
                    scannerClosed = true;
                }
            }
        }
    }

    /*
     * Regex that will work for straight filenames and for reference names.
     * If reference, then the regex has more than just one group.  Group 1 is
     * this files id.  Group 2 the referenced region name, etc.
     */
    private static Pattern REF_NAME_PARSER =
            Pattern.compile("^(\\d+)(?:\\.(.+))?$");

    private static final String BLOOMFILTER_FILE_NAME = "filter";

    final Memcache memcache = new Memcache();
    private final Path basedir;
    private final HRegionInfo info;
    private final HColumnDescriptor family;
    private final SequenceFile.CompressionType compression;
    final FileSystem fs;
    private final HBaseConfiguration conf;
    private final Path filterDir;
    final Filter bloomFilter;
    private final Path compactionDir;

    private final Integer compactLock = new Integer(0);
    private final Integer flushLock = new Integer(0);

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    final AtomicInteger activeScanners = new AtomicInteger(0);

    final String storeName;

    /*
     * Sorted Map of readers keyed by sequence id (Most recent should be last in
     * in list).
     */
    final SortedMap<Long, HStoreFile> storefiles =
            Collections.synchronizedSortedMap(new TreeMap<Long, HStoreFile>());

    /*
     * Sorted Map of readers keyed by sequence id (Most recent should be last in
     * in list).
     */
    private final SortedMap<Long, MapFile.Reader> readers =
            new TreeMap<Long, MapFile.Reader>();

    private volatile long maxSeqId;
    private final int compactionThreshold;
    private final ReentrantReadWriteLock newScannerLock =
            new ReentrantReadWriteLock();

    /**
     * An HStore is a set of zero or more MapFiles, which stretch backwards over
     * time.  A given HStore is responsible for a certain set of columns for a
     * row in the HRegion.
     *
     * <p>The HRegion starts writing to its set of HStores when the HRegion's
     * memcache is flushed.  This results in a round of new MapFiles, one for
     * each HStore.
     *
     * <p>There's no reason to consider append-logging at this level; all logging
     * and locking is handled at the HRegion level.  HStore just provides
     * services to manage sets of MapFiles.  One of the most important of those
     * services is MapFile-compaction services.
     *
     * <p>The only thing having to do with logs that HStore needs to deal with is
     * the reconstructionLog.  This is a segment of an HRegion's log that might
     * NOT be present upon startup.  If the param is NULL, there's nothing to do.
     * If the param is non-NULL, we need to process the log to reconstruct
     * a TreeMap that might not have been written to disk before the process
     * died.
     *
     * <p>It's assumed that after this constructor returns, the reconstructionLog
     * file will be deleted (by whoever has instantiated the HStore).
     *
     * @param basedir           qualified path under which the region directory lives
     * @param info              HRegionInfo for this region
     * @param family            HColumnDescriptor for this column
     * @param fs                file system object
     * @param reconstructionLog existing log file to apply if any
     * @param conf              configuration object
     * @throws IOException
     */
    HStore(Path basedir, HRegionInfo info, HColumnDescriptor family,
           FileSystem fs, Path reconstructionLog, HBaseConfiguration conf)
            throws IOException {
        // zeng: table dir
        this.basedir = basedir;
        // zeng: region info
        this.info = info;
        // zeng: column family descriptor
        this.family = family;
        // zeng: dfs
        this.fs = fs;

        this.conf = conf;

        // zeng: table dir/compaction.dir
        this.compactionDir = HRegion.getCompactionDir(basedir);

        // zeng: hstore name
        this.storeName =
                this.info.getEncodedName() + "/" + this.family.getFamilyName();

        // zeng: 压缩类型
        if (family.getCompression() == HColumnDescriptor.CompressionType.BLOCK) {
            this.compression = SequenceFile.CompressionType.BLOCK;
        } else if (family.getCompression() == HColumnDescriptor.CompressionType.RECORD) {
            this.compression = SequenceFile.CompressionType.RECORD;
        } else {
            this.compression = SequenceFile.CompressionType.NONE;
        }

        // zeng: table dir/ encoded region name / family name / mapfiles
        Path mapdir = HStoreFile.getMapDir(basedir, info.getEncodedName(), family.getFamilyName());
        if (!fs.exists(mapdir)) {
            fs.mkdirs(mapdir);
        }

        // zeng: table dir/ encoded region name / family name / info
        Path infodir = HStoreFile.getInfoDir(basedir, info.getEncodedName(), family.getFamilyName());
        if (!fs.exists(infodir)) {
            fs.mkdirs(infodir);
        }

        if (family.getBloomFilter() == null) {
            this.filterDir = null;
            this.bloomFilter = null;
        } else {
            // zeng: table dir/ encoded region name / family name / filter
            this.filterDir = HStoreFile.getFilterDir(basedir, info.getEncodedName(), family.getFamilyName());
            if (!fs.exists(filterDir)) {
                fs.mkdirs(filterDir);
            }

            // zeng: 加载或者新建bloomfilter
            this.bloomFilter = loadOrCreateBloomFilter();
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("starting " + storeName +
                    ((reconstructionLog == null || !fs.exists(reconstructionLog)) ?
                            " (no reconstruction log)" :
                            " with reconstruction log: " + reconstructionLog.toString()));
        }

        // Go through the 'mapdir' and 'infodir' together, make sure that all
        // MapFiles are in a reliable state.  Every entry in 'mapdir' must have a
        // corresponding one in 'loginfodir'. Without a corresponding log info
        // file, the entry in 'mapdir' must be deleted.
        // zeng: 加载region family 目录下的hfile
        List<HStoreFile> hstoreFiles = loadHStoreFiles(infodir, mapdir);
        for (HStoreFile hsf : hstoreFiles) {
            this.storefiles.put(Long.valueOf(hsf.loadInfo(fs)), hsf);
        }

        // Now go through all the HSTORE_LOGINFOFILEs and figure out the
        // most-recent log-seq-ID that's present.  The most-recent such ID means we
        // can ignore all log messages up to and including that ID (because they're
        // already reflected in the TreeMaps).
        //
        // If the HSTORE_LOGINFOFILE doesn't contain a number, just ignore it. That
        // means it was built prior to the previous run of HStore, and so it cannot
        // contain any updates also contained in the log.

        // zeng: 所有hfile中最大的sequence id
        this.maxSeqId = getMaxSequenceId(hstoreFiles);
        if (LOG.isDebugEnabled()) {
            LOG.debug("maximum sequence id for hstore " + storeName + " is " +
                    this.maxSeqId);
        }

        // zeng: 根据hlog恢复memcache
        doReconstructionLog(reconstructionLog, maxSeqId);

        // By default, we compact if an HStore has more than
        // MIN_COMMITS_FOR_COMPACTION map files
        this.compactionThreshold =
                conf.getInt("hbase.hstore.compactionThreshold", 3);

        // We used to compact in here before bringing the store online.  Instead
        // get it online quick even if it needs compactions so we can start
        // taking updates as soon as possible (Once online, can take updates even
        // during a compaction).

        // Move maxSeqId on by one. Why here?  And not in HRegion?
        this.maxSeqId += 1;

        // Finally, start up all the map readers! (There could be more than one
        // since we haven't compacted yet.)
        // zeng: new BloomFilterMapFile.Reader
        for (Map.Entry<Long, HStoreFile> e : this.storefiles.entrySet()) {
            this.readers.put(e.getKey(), e.getValue().getReader(this.fs, this.bloomFilter));
        }
    }

    /*
     * @param hstoreFiles
     * @return Maximum sequence number found or -1.
     * @throws IOException
     */
    private long getMaxSequenceId(final List<HStoreFile> hstoreFiles)
            throws IOException {
        long maxSeqID = -1;
        for (HStoreFile hsf : hstoreFiles) {
            long seqid = hsf.loadInfo(fs);
            if (seqid > 0) {
                if (seqid > maxSeqID) {
                    maxSeqID = seqid;
                }
            }
        }

        return maxSeqID;
    }

    long getMaxSequenceId() {
        return this.maxSeqId;
    }

    /*
     * Read the reconstructionLog to see whether we need to build a brand-new
     * MapFile out of non-flushed log entries.
     *
     * We can ignore any log message that has a sequence ID that's equal to or
     * lower than maxSeqID.  (Because we know such log messages are already
     * reflected in the MapFiles.)
     */
    private void doReconstructionLog(final Path reconstructionLog, final long maxSeqID)
            throws UnsupportedEncodingException, IOException {

        if (reconstructionLog == null || !fs.exists(reconstructionLog)) {
            // Nothing to do.
            return;
        }
        long maxSeqIdInLog = -1;
        TreeMap<HStoreKey, byte[]> reconstructedCache = new TreeMap<HStoreKey, byte[]>();

        // zeng: file reader
        SequenceFile.Reader logReader = new SequenceFile.Reader(this.fs, reconstructionLog, this.conf);

        try {
            HLogKey key = new HLogKey();
            HLogEdit val = new HLogEdit();
            long skippedEdits = 0;
            long editsCount = 0;
            while (logReader.next(key, val)) {  // zeng: 下一条

                // zeng: log重放中最大的sequence id
                maxSeqIdInLog = Math.max(maxSeqIdInLog, key.getLogSeqNum());

                // zeng: 如果小于hfile中的sequence id, 说明已经flush了, 不需要处理
                if (key.getLogSeqNum() <= maxSeqID) {
                    skippedEdits++;
                    continue;
                }

                // Check this edit is for me. Also, guard against writing
                // METACOLUMN info such as HBASE::CACHEFLUSH entries
                // zeng: 不是同一个family, 不需要处理
                Text column = val.getColumn();
                if (column.equals(HLog.METACOLUMN) || !key.getRegionName().equals(info.getRegionName()) || !HStoreKey.extractFamily(column).equals(family.getFamilyName())) {
                    continue;
                }

                // zeng: reconstructedCache.put
                HStoreKey k = new HStoreKey(key.getRow(), column, val.getTimestamp());
                reconstructedCache.put(k, val.getVal());
                editsCount++;

            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("Applied " + editsCount + ", skipped " + skippedEdits +
                        " because sequence id <= " + maxSeqID);
            }
        } finally {
            logReader.close();
        }

        if (reconstructedCache.size() > 0) {
            // We create a "virtual flush" at maxSeqIdInLog+1.
            if (LOG.isDebugEnabled()) {
                LOG.debug("flushing reconstructionCache");
            }

            // zeng: reconstructedCache flush to hfile
            internalFlushCache(reconstructedCache, maxSeqIdInLog + 1);
        }
    }

    /*
     * Creates a series of HStoreFiles loaded from the given directory.
     * There must be a matching 'mapdir' and 'loginfo' pair of files.
     * If only one exists, we'll delete it.
     *
     * @param infodir qualified path for info file directory
     * @param mapdir qualified path for map file directory
     * @throws IOException
     */
    private List<HStoreFile> loadHStoreFiles(Path infodir, Path mapdir)
            throws IOException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("infodir: " + infodir.toString() + " mapdir: " +
                    mapdir.toString());
        }
        // Look first at info files.  If a reference, these contain info we need
        // to create the HStoreFile.
        Path infofiles[] = fs.listPaths(new Path[]{infodir});
        ArrayList<HStoreFile> results = new ArrayList<HStoreFile>(infofiles.length);
        ArrayList<Path> mapfiles = new ArrayList<Path>(infofiles.length);

        for (Path p : infofiles) {
            Matcher m = REF_NAME_PARSER.matcher(p.getName());
            /*
             *  *  *  *  *  N O T E  *  *  *  *  *
             *
             *  We call isReference(Path, Matcher) here because it calls
             *  Matcher.matches() which must be called before Matcher.group(int)
             *  and we don't want to call Matcher.matches() twice.
             *
             *  *  *  *  *  N O T E  *  *  *  *  *
             */
            // zeng: 文件名带region name表示是引用其他region的hfile
            boolean isReference = isReference(p, m);

            // zeng: file id
            long fid = Long.parseLong(m.group(1));

            HStoreFile curfile = null;

            // zeng: 读取引用信息
            HStoreFile.Reference reference = null;
            if (isReference) {
                reference = readSplitInfo(p, fs);
            }

            // zeng: hfile
            curfile = new HStoreFile(conf, fs, basedir, info.getEncodedName(), family.getFamilyName(), fid, reference);

            // zeng: 如果map file不存在, 那么删除info file
            Path mapfile = curfile.getMapFilePath();
            if (!fs.exists(mapfile)) {
                fs.delete(curfile.getInfoFilePath());
                LOG.warn("Mapfile " + mapfile.toString() + " does not exist. " +
                        "Cleaned up info file.  Continuing...");
                continue;
            }

            // TODO: Confirm referent exists.

            // Found map and sympathetic info file.  Add this hstorefile to result.
            results.add(curfile);

            // Keep list of sympathetic data mapfiles for cleaning info dir in next
            // section.  Make sure path is fully qualified for compare.
            mapfiles.add(mapfile);
        }

        // zeng: info file目录下不存在的, map file目录下也要删除
        // List paths by experience returns fully qualified names -- at least when
        // running on a mini hdfs cluster.
        Path datfiles[] = fs.listPaths(new Path[]{mapdir});
        for (int i = 0; i < datfiles.length; i++) {
            // If does not have sympathetic info file, delete.
            if (!mapfiles.contains(fs.makeQualified(datfiles[i]))) {
                fs.delete(datfiles[i]);
            }
        }

        return results;
    }

    //////////////////////////////////////////////////////////////////////////////
    // Bloom filters
    //////////////////////////////////////////////////////////////////////////////

    /**
     * Called by constructor if a bloom filter is enabled for this column family.
     * If the HStore already exists, it will read in the bloom filter saved
     * previously. Otherwise, it will create a new bloom filter.
     */
    private Filter loadOrCreateBloomFilter() throws IOException {
        // zeng: bloomfilter file
        Path filterFile = new Path(filterDir, BLOOMFILTER_FILE_NAME);

        Filter bloomFilter = null;

        if (fs.exists(filterFile)) {    // zeng: 如果存在文件
            if (LOG.isDebugEnabled()) {
                LOG.debug("loading bloom filter for " + this.storeName);
            }

            BloomFilterDescriptor.BloomFilterType type = family.getBloomFilter().filterType;

            switch (type) {

                case BLOOMFILTER:
                    bloomFilter = new BloomFilter();
                    break;

                case COUNTING_BLOOMFILTER:
                    bloomFilter = new CountingBloomFilter();
                    break;

                case RETOUCHED_BLOOMFILTER:
                    bloomFilter = new RetouchedBloomFilter();
                    break;

                default:
                    throw new IllegalArgumentException("unknown bloom filter type: " +
                            type);
            }


            // zeng: 加载文件中的bloomfilter
            FSDataInputStream in = fs.open(filterFile);
            try {
                bloomFilter.readFields(in);
            } finally {
                fs.close();
            }

        } else {    // zeng: 新建bloomfilter
            if (LOG.isDebugEnabled()) {
                LOG.debug("creating bloom filter for " + this.storeName);
            }

            BloomFilterDescriptor.BloomFilterType type = family.getBloomFilter().filterType;

            switch (type) {

                case BLOOMFILTER:
                    bloomFilter = new BloomFilter(family.getBloomFilter().vectorSize,
                            family.getBloomFilter().nbHash);
                    break;

                case COUNTING_BLOOMFILTER:
                    bloomFilter =
                            new CountingBloomFilter(family.getBloomFilter().vectorSize,
                                    family.getBloomFilter().nbHash);
                    break;

                case RETOUCHED_BLOOMFILTER:
                    bloomFilter =
                            new RetouchedBloomFilter(family.getBloomFilter().vectorSize,
                                    family.getBloomFilter().nbHash);
            }
        }

        return bloomFilter;
    }

    /**
     * Flushes bloom filter to disk
     *
     * @throws IOException
     */
    private void flushBloomFilter() throws IOException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("flushing bloom filter for " + this.storeName);
        }

        // zeng: table dir/ encoded region name / family name / filter / filter
        FSDataOutputStream out = fs.create(new Path(filterDir, BLOOMFILTER_FILE_NAME));
        try {
            // zeng: bloomfilter写入文件
            bloomFilter.write(out);
        } finally {
            out.close();
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("flushed bloom filter for " + this.storeName);
        }
    }

    //////////////////////////////////////////////////////////////////////////////
    // End bloom filters
    //////////////////////////////////////////////////////////////////////////////

    /**
     * Adds a value to the memcache
     *
     * @param key
     * @param value
     */
    void add(HStoreKey key, byte[] value) {
        lock.readLock().lock();
        try {

            // zeng: 写入memcache中
            this.memcache.add(key, value);

        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Close all the MapFile readers
     * <p>
     * We don't need to worry about subsequent requests because the HRegion holds
     * a write lock that will prevent any more reads or writes.
     *
     * @throws IOException
     */
    List<HStoreFile> close() throws IOException {
        ArrayList<HStoreFile> result = null;
        this.lock.writeLock().lock();
        try {
            for (MapFile.Reader reader : this.readers.values()) {
                reader.close();
            }
            this.readers.clear();
            result = new ArrayList<HStoreFile>(storefiles.values());
            this.storefiles.clear();
            LOG.debug("closed " + this.storeName);
            return result;
        } finally {
            this.lock.writeLock().unlock();
        }
    }


    //////////////////////////////////////////////////////////////////////////////
    // Flush changes to disk
    //////////////////////////////////////////////////////////////////////////////

    /**
     * Prior to doing a cache flush, we need to snapshot the memcache. Locking is
     * handled by the memcache.
     */
    void snapshotMemcache() {
        this.memcache.snapshot();
    }

    /**
     * Write out a brand-new set of items to the disk.
     * <p>
     * We should only store key/vals that are appropriate for the data-columns
     * stored in this HStore.
     * <p>
     * Also, we are not expecting any reads of this MapFile just yet.
     * <p>
     * Return the entire list of HStoreFiles currently used by the HStore.
     *
     * @param logCacheFlushId flush sequence number
     * @throws IOException
     */
    void flushCache(final long logCacheFlushId) throws IOException {
        // zeng: memcache.getSnapshot()
        internalFlushCache(memcache.getSnapshot(), logCacheFlushId);
    }

    // zeng: memcache 写入 hfile
    private void internalFlushCache(SortedMap<HStoreKey, byte[]> cache, long logCacheFlushId) throws IOException {

        synchronized (flushLock) {
            // zeng: nwe HStoreFile
            // A. Write the Maps out to the disk
            HStoreFile flushedFile = new HStoreFile(conf, fs, basedir, info.getEncodedName(), family.getFamilyName(), -1L, null);
            String name = flushedFile.toString();

            // zeng: new BloomFilterMapFile.Writer
            MapFile.Writer out = flushedFile.getWriter(this.fs, this.compression, this.bloomFilter);

            // Here we tried picking up an existing HStoreFile from disk and
            // interlacing the memcache flush compacting as we go.  The notion was
            // that interlacing would take as long as a pure flush with the added
            // benefit of having one less file in the store.  Experiments showed that
            // it takes two to three times the amount of time flushing -- more column
            // families makes it so the two timings come closer together -- but it
            // also complicates the flush. The code was removed.  Needed work picking
            // which file to interlace (favor references first, etc.)
            //
            // Related, looks like 'merging compactions' in BigTable paper interlaces
            // a memcache flush.  We don't.
            int entries = 0;
            try {
                for (Map.Entry<HStoreKey, byte[]> es : cache.entrySet()) {  // zeng: 遍历snapshot里的item
                    // zeng: key
                    HStoreKey curkey = es.getKey();
                    // zeng: family
                    TextSequence f = HStoreKey.extractFamily(curkey.getColumn());

                    if (f.equals(this.family.getFamilyName())) {    // zeng: 如果是这个family的item
                        entries++;

                        // zeng: BloomFilterMapFile.Writer.append
                        out.append(curkey, new ImmutableBytesWritable(es.getValue()));
                    }
                }
            } finally {
                out.close();
            }

            // zeng: flush id 写入 info 下文件中
            // B. Write out the log sequence number that corresponds to this output
            // MapFile.  The MapFile is current up to and including the log seq num.
            flushedFile.writeInfo(fs, logCacheFlushId);

            // zeng: bloomfilter写入文件中
            // C. Flush the bloom filter if any
            if (bloomFilter != null) {
                flushBloomFilter();
            }

            // zeng: 加载新建的hfile
            // D. Finally, make the new MapFile available.
            this.lock.writeLock().lock();
            try {
                Long flushid = Long.valueOf(logCacheFlushId);
                // Open the map file reader.
                // zeng: reader
                this.readers.put(flushid, flushedFile.getReader(this.fs, this.bloomFilter));
                // zeng: hfile
                this.storefiles.put(flushid, flushedFile);

                if (LOG.isDebugEnabled()) {
                    LOG.debug("Added " + name + " with " + entries +
                            " entries, sequence id " + logCacheFlushId + ", and size " +
                            StringUtils.humanReadableInt(flushedFile.length()) + " for " +
                            this.storeName);
                }

            } finally {
                this.lock.writeLock().unlock();
            }
            return;
        }
    }

    //////////////////////////////////////////////////////////////////////////////
    // Compaction
    //////////////////////////////////////////////////////////////////////////////

    /**
     * @return True if this store needs compaction.
     */
    boolean needsCompaction() {
        // zeng: 默认hfile大于3时compact, 或者hfile是引用其他region的hfile时compact
        return this.storefiles != null && (this.storefiles.size() >= this.compactionThreshold || hasReferences());
    }

    /*
     * @return True if this store has references.
     */
    private boolean hasReferences() {
        if (this.storefiles != null) {
            for (HStoreFile hsf : this.storefiles.values()) {
                if (hsf.isReference()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Compact the back-HStores.  This method may take some time, so the calling
     * thread must be able to block for long periods.
     *
     * <p>During this time, the HStore can work as usual, getting values from
     * MapFiles and writing new MapFiles from the Memcache.
     * <p>
     * Existing MapFiles are not destroyed until the new compacted TreeMap is
     * completely written-out to disk.
     * <p>
     * The compactLock prevents multiple simultaneous compactions.
     * The structureLock prevents us from interfering with other write operations.
     * <p>
     * We don't want to hold the structureLock for the whole time, as a compact()
     * can be lengthy and we want to allow cache-flushes during this period.
     *
     * @return true if compaction completed successfully
     * @throws IOException
     */
    boolean compact() throws IOException {
        synchronized (compactLock) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("started compaction of " + storefiles.size() +
                        " files using " + compactionDir.toString() + " for " +
                        this.storeName);
            }

            // Storefiles are keyed by sequence id. The oldest file comes first.
            // We need to return out of here a List that has the newest file first.
            // zeng: hfile从新到旧排列
            List<HStoreFile> filesToCompact = new ArrayList<HStoreFile>(this.storefiles.values());
            Collections.reverse(filesToCompact);

            // zeng: compact条件
            if (filesToCompact.size() < 1 || (filesToCompact.size() == 1 && !filesToCompact.get(0).isReference())) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("nothing to compact for " + this.storeName);
                }
                return false;
            }

            // zeng: compact中间目录
            if (!fs.exists(compactionDir) && !fs.mkdirs(compactionDir)) {
                LOG.warn("Mkdir on " + compactionDir.toString() + " failed");
                return false;
            }

            // zeng: new HStoreFile
            // Step through them, writing to the brand-new MapFile
            HStoreFile compactedOutputFile = new HStoreFile(
                    conf, fs, this.compactionDir, info.getEncodedName(), family.getFamilyName(),
                    -1L, null
            );
            // zeng: BloomFilterMapFile.Writer
            MapFile.Writer compactedOut = compactedOutputFile.getWriter(this.fs, this.compression, this.bloomFilter);

            try {
                // zeng: compact
                compactHStoreFiles(compactedOut, filesToCompact);
            } finally {
                compactedOut.close();
            }

            // zeng: 这些hfile中最打的sequence id
            // Now, write out an HSTORE_LOGINFOFILE for the brand-new TreeMap.
            // Compute max-sequenceID seen in any of the to-be-compacted TreeMaps.
            long maxId = getMaxSequenceId(filesToCompact);
            // zeng: 写入 新hfile 的 info文件
            compactedOutputFile.writeInfo(fs, maxId);

            // zeng: 提交compact
            // Move the compaction into place.
            completeCompaction(filesToCompact, compactedOutputFile);

            return true;
        }
    }

    /*
     * Compact passed <code>toCompactFiles</code> into <code>compactedOut</code>.
     * We create a new set of MapFile.Reader objects so we don't screw up the
     * caching associated with the currently-loaded ones. Our iteration-based
     * access pattern is practically designed to ruin the cache.
     *
     * We work by opening a single MapFile.Reader for each file, and iterating
     * through them in parallel. We always increment the lowest-ranked one.
     * Updates to a single row/column will appear ranked by timestamp. This allows
     * us to throw out deleted values or obsolete versions. @param compactedOut
     * @param toCompactFiles @throws IOException
     */
    private void compactHStoreFiles(final MapFile.Writer compactedOut,
                                    final List<HStoreFile> toCompactFiles) throws IOException {

        int size = toCompactFiles.size();
        CompactionReader[] rdrs = new CompactionReader[size];
        int index = 0;
        for (HStoreFile hsf : toCompactFiles) {
            try {
                // zeng: hfile reader
                rdrs[index++] = new MapFileCompactionReader(hsf.getReader(fs, bloomFilter));
            } catch (IOException e) {
                // Add info about which file threw exception. It may not be in the
                // exception message so output a message here where we know the
                // culprit.
                LOG.warn("Failed with " + e.toString() + ": HStoreFile=" +
                        hsf.toString() + (hsf.isReference() ? ", Reference=" +
                        hsf.getReference().toString() : "") + " for Store=" +
                        this.storeName);
                closeCompactionReaders(rdrs);
                throw e;
            }
        }
        try {
            HStoreKey[] keys = new HStoreKey[rdrs.length];
            ImmutableBytesWritable[] vals = new ImmutableBytesWritable[rdrs.length];
            boolean[] done = new boolean[rdrs.length];
            for (int i = 0; i < rdrs.length; i++) {
                keys[i] = new HStoreKey();
                vals[i] = new ImmutableBytesWritable();
                done[i] = false;
            }

            // Now, advance through the readers in order.  This will have the
            // effect of a run-time sort of the entire dataset.
            int numDone = 0;
            for (int i = 0; i < rdrs.length; i++) {
                rdrs[i].reset();

                // zeng: 读取hfile下一个key value
                done[i] = !rdrs[i].next(keys[i], vals[i]);
                if (done[i]) {
                    numDone++;
                }
            }

            int timesSeen = 0;
            Text lastRow = new Text();
            Text lastColumn = new Text();
            // Map of a row deletes keyed by column with a list of timestamps for value
            Map<Text, List<Long>> deletes = null;
            while (numDone < done.length) { // zeng: 直到所有hfile都读完
                // Find the reader with the smallest key.  If two files have same key
                // but different values -- i.e. one is delete and other is non-delete
                // value -- we will find the first, the one that was written later and
                // therefore the one whose value should make it out to the compacted
                // store file.

                // zeng: 哪个hfile当前key最小
                int smallestKey = -1;
                for (int i = 0; i < rdrs.length; i++) {
                    if (done[i]) {
                        continue;
                    }
                    if (smallestKey < 0) {
                        smallestKey = i;
                    } else {
                        if (keys[i].compareTo(keys[smallestKey]) < 0) {
                            smallestKey = i;
                        }
                    }
                }

                // Reflect the current key/val in the output
                // zeng: key
                HStoreKey sk = keys[smallestKey];

                // zeng: 同一行同一列 出现过几次了
                if (lastRow.equals(sk.getRow()) && lastColumn.equals(sk.getColumn())) {
                    timesSeen++;
                } else {
                    timesSeen = 1;
                    // We are on to a new row.  Create a new deletes list.
                    deletes = new HashMap<Text, List<Long>>();
                }

                // zeng: value
                byte[] value = (vals[smallestKey] == null) ? null : vals[smallestKey].get();

                // zeng: TODO delete不写,只写旧版本数据, 怎么知道最新版本是delete的呢?
                // zeng: 不是删除的cell, 且当前获取的cell数不大于 保留的cell数上限
                if (!isDeleted(sk, value, false, deletes) && timesSeen <= family.getMaxVersions()) {
                    // Keep old versions until we have maxVersions worth.
                    // Then just skip them.
                    if (sk.getRow().getLength() != 0 && sk.getColumn().getLength() != 0) {
                        // Only write out objects which have a non-zero length key and
                        // value
                        // zeng: 写入cell
                        compactedOut.append(sk, vals[smallestKey]);
                    }
                }

                // zeng: 上次scan到的行
                // Update last-seen items
                lastRow.set(sk.getRow());
                // zeng: 上次scan到的列
                lastColumn.set(sk.getColumn());

                // Advance the smallest key.  If that reader's all finished, then
                // mark it as done.
                if (!rdrs[smallestKey].next(keys[smallestKey], vals[smallestKey])) {    // zeng: 下一个cell
                    done[smallestKey] = true;
                    rdrs[smallestKey].close();
                    rdrs[smallestKey] = null;
                    numDone++;
                }
            }
        } finally {
            closeCompactionReaders(rdrs);
        }
    }

    private void closeCompactionReaders(final CompactionReader[] rdrs) {
        for (int i = 0; i < rdrs.length; i++) {
            if (rdrs[i] != null) {
                try {
                    rdrs[i].close();
                } catch (IOException e) {
                    LOG.warn("Exception closing reader for " + this.storeName, e);
                }
            }
        }
    }

    /**
     * Interface for generic reader for compactions
     */
    interface CompactionReader {

        /**
         * Closes the reader
         *
         * @throws IOException
         */
        public void close() throws IOException;

        /**
         * Get the next key/value pair
         *
         * @param key
         * @param val
         * @return true if more data was returned
         * @throws IOException
         */
        public boolean next(WritableComparable key, Writable val)
                throws IOException;

        /**
         * Resets the reader
         *
         * @throws IOException
         */
        public void reset() throws IOException;
    }

    // zeng: TODO

    /**
     * A compaction reader for MapFile
     */
    static class MapFileCompactionReader implements CompactionReader {
        final MapFile.Reader reader;

        MapFileCompactionReader(final MapFile.Reader r) {
            this.reader = r;
        }

        /**
         * {@inheritDoc}
         */
        public void close() throws IOException {
            this.reader.close();
        }

        /**
         * {@inheritDoc}
         */
        public boolean next(WritableComparable key, Writable val)
                throws IOException {
            return this.reader.next(key, val);
        }

        /**
         * {@inheritDoc}
         */
        public void reset() throws IOException {
            this.reader.reset();
        }
    }

    /*
     * Check if this is cell is deleted.
     * If a memcache and a deletes, check key does not have an entry filled.
     * Otherwise, check value is not the <code>HGlobals.deleteBytes</code> value.
     * If passed value IS deleteBytes, then it is added to the passed
     * deletes map.
     * @param hsk
     * @param value
     * @param checkMemcache true if the memcache should be consulted
     * @param deletes Map keyed by column with a value of timestamp. Can be null.
     * If non-null and passed value is HGlobals.deleteBytes, then we add to this
     * map.
     * @return True if this is a deleted cell.  Adds the passed deletes map if
     * passed value is HGlobals.deleteBytes.
     */
    private boolean isDeleted(
            final HStoreKey hsk, final byte[] value,
            final boolean checkMemcache, final Map<Text, List<Long>> deletes
    ) {
        // zeng: memcache里是该key是否是delete操作
        if (checkMemcache && memcache.isDeleted(hsk)) {
            return true;
        }

        // zeng: 版本号在deletes数组里(也就是说当前cell有对应的tombstone)
        List<Long> timestamps = (deletes == null) ? null : deletes.get(hsk.getColumn());
        if (timestamps != null && timestamps.contains(Long.valueOf(hsk.getTimestamp()))) {
            return true;
        }

        // zeng: 没有值
        if (value == null) {
            // If a null value, shouldn't be in here.  Mark it as deleted cell.
            return true;
        }

        // zeng: 值不是 HBASE::DELETEVAL
        if (!HLogEdit.isDeleted(value)) {
            return false;
        }

        // Cell has delete value.  Save it into deletes.
        if (deletes != null) {
            if (timestamps == null) {
                timestamps = new ArrayList<Long>();
                deletes.put(hsk.getColumn(), timestamps);
            }

            // We know its not already in the deletes array else we'd have returned
            // earlier so no need to test if timestamps already has this value.

            // zeng: 版本号放入deletes里
            timestamps.add(Long.valueOf(hsk.getTimestamp()));
        }

        // zeng: 是
        return true;
    }

    /**
     * It's assumed that the compactLock  will be acquired prior to calling this
     * method!  Otherwise, it is not thread-safe!
     * <p>
     * It works by processing a compaction that's been written to disk.
     *
     * <p>It is usually invoked at the end of a compaction, but might also be
     * invoked at HStore startup, if the prior execution died midway through.
     *
     * <p>Moving the compacted TreeMap into place means:
     * <pre>
     * 1) Wait for active scanners to exit
     * 2) Acquiring the write-lock
     * 3) Figuring out what MapFiles are going to be replaced
     * 4) Moving the new compacted MapFile into place
     * 5) Unloading all the replaced MapFiles.
     * 6) Deleting all the old MapFile files.
     * 7) Loading the new TreeMap.
     * 8) Releasing the write-lock
     * 9) Allow new scanners to proceed.
     * </pre>
     *
     * @param compactedFiles list of files that were compacted
     * @param compactedFile  HStoreFile that is the result of the compaction
     * @throws IOException
     */
    private void completeCompaction(List<HStoreFile> compactedFiles, HStoreFile compactedFile) throws IOException {

        // 1. Wait for active scanners to exit

        newScannerLock.writeLock().lock();                  // prevent new scanners

        try {
            // zeng: 等待 activeScanners 中没有scanner
            synchronized (activeScanners) {
                while (activeScanners.get() != 0) {
                    try {
                        activeScanners.wait();
                    } catch (InterruptedException e) {
                        // continue
                    }
                }

                // zeng: hstore写锁
                // 2. Acquiring the HStore write-lock
                this.lock.writeLock().lock();
            }

            try {
                // 3. Moving the new MapFile into place.
                // zeng: 工作目录下的hfile
                HStoreFile finalCompactedFile = new HStoreFile(conf, fs, basedir, info.getEncodedName(), family.getFamilyName(), -1, null);

                if (LOG.isDebugEnabled()) {
                    LOG.debug("moving " + compactedFile.toString() + " in " +
                            this.compactionDir.toString() + " to " +
                            finalCompactedFile.toString() + " in " + basedir.toString() +
                            " for " + this.storeName);
                }

                // zeng: 从 compact目录 移动到 工作目录
                if (!compactedFile.rename(this.fs, finalCompactedFile)) {
                    LOG.error("Failed move of compacted file " + finalCompactedFile.toString() + " for " + this.storeName);
                    return;
                }


                // 4. and 5. Unload all the replaced MapFiles, close and delete.
                List<Long> toDelete = new ArrayList<Long>();
                for (Map.Entry<Long, HStoreFile> e : this.storefiles.entrySet()) {
                    // zeng: 不是被compact的hfile
                    if (!compactedFiles.contains(e.getValue())) {
                        continue;
                    }

                    // zeng: 从reader中移除
                    Long key = e.getKey();
                    MapFile.Reader reader = this.readers.remove(key);
                    if (reader != null) {
                        // zeng: reader close
                        reader.close();
                    }

                    // zeng: toDelete
                    toDelete.add(key);
                }

                try {
                    // zeng: 删除hfile
                    for (Long key : toDelete) {
                        HStoreFile hsf = this.storefiles.remove(key);
                        hsf.delete();
                    }

                    // zeng: load compact完的hfile
                    // 6. Loading the new TreeMap.
                    Long orderVal = Long.valueOf(finalCompactedFile.loadInfo(fs));
                    this.readers.put(orderVal, finalCompactedFile.getReader(this.fs, this.bloomFilter));
                    this.storefiles.put(orderVal, finalCompactedFile);

                } catch (IOException e) {
                    e = RemoteExceptionHandler.checkIOException(e);
                    LOG.error("Failed replacing compacted files for " + this.storeName +
                            ". Compacted file is " + finalCompactedFile.toString() +
                            ".  Files replaced are " + compactedFiles.toString() +
                            " some of which may have been already removed", e);
                }
            } finally {
                // zeng: 写锁
                // 7. Releasing the write-lock
                this.lock.writeLock().unlock();
            }
        } finally {
            // zeng: scanner写锁
            // 8. Allow new scanners to proceed.
            newScannerLock.writeLock().unlock();
        }
    }

    //////////////////////////////////////////////////////////////////////////////
    // Accessors.
    // (This is the only section that is directly useful!)
    //////////////////////////////////////////////////////////////////////////////

    /**
     * Return all the available columns for the given key.  The key indicates a
     * row and timestamp, but not a column name.
     * <p>
     * The returned object should map column names to byte arrays (byte[]).
     */
    void getFull(HStoreKey key, TreeMap<Text, byte[]> results)
            throws IOException {
        Map<Text, Long> deletes = new HashMap<Text, Long>();

        if (key == null) {
            return;
        }

        this.lock.readLock().lock();

        // zeng: memcache中获取数据
        memcache.getFull(key, deletes, results);

        try {
            // zeng: 所有hfile
            MapFile.Reader[] maparray = getReaders();
            for (int i = maparray.length - 1; i >= 0; i--) {
                MapFile.Reader map = maparray[i];
                // zeng: hfile中获取数据
                getFullFromMapFile(map, key, deletes, results);
            }
        } finally {
            this.lock.readLock().unlock();
        }
    }

    private void getFullFromMapFile(
            MapFile.Reader map, HStoreKey key,
            Map<Text, Long> deletes, TreeMap<Text, byte[]> results
    ) throws IOException {

        synchronized (map) {
            // zeng: file first position
            map.reset();

            ImmutableBytesWritable readval = new ImmutableBytesWritable();

            // zeng: 等于key 或者 大于key(时间戳更早) 的最近邻的key
            HStoreKey readkey = (HStoreKey) map.getClosest(key, readval);

            if (readkey == null) {
                return;
            }
            do {
                Text readcol = readkey.getColumn();

                // if there isn't already a value in the results map, and the key we
                // just read matches, then we'll consider it
                if (!results.containsKey(readcol) && key.matchesWithoutColumn(readkey)) {    // zeng: 每一列只获取一个版本 . 同一个行timestamp小于传参的timestamp

                    // if the value of the cell we're looking at right now is a delete,
                    // we need to treat it differently
                    if (HLogEdit.isDeleted(readval.get())) {    // zeng: value不为HBASE::DELETEVAL

                        // if it's not already recorded as a delete or recorded with a more
                        // recent delete timestamp, record it for later
                        // zeng: deletes中存储该column的delete type的最新版本
                        if (!deletes.containsKey(readcol) || deletes.get(readcol).longValue() < readkey.getTimestamp()) {
                            deletes.put(new Text(readcol), readkey.getTimestamp());
                        }

                    } else if (!(deletes.containsKey(readcol) && deletes.get(readcol).longValue() >= readkey.getTimestamp())) { // zeng: 如果 cell timestamp 比 delete type cell 的timestamp 大

                        // So the cell itself isn't a delete, but there may be a delete
                        // pending from earlier in our search. Only record this result if
                        // there aren't any pending deletes.
                        if (!(deletes.containsKey(readcol) && deletes.get(readcol).longValue() >= readkey.getTimestamp())) {
                            // zeng: add to result
                            results.put(new Text(readcol), readval.get());

                            // need to reinstantiate the readval so we can reuse it,
                            // otherwise next iteration will destroy our result
                            readval = new ImmutableBytesWritable();
                        }

                    }

                } else if (key.getRow().compareTo(readkey.getRow()) < 0) {  // zeng: rowkey不一样了
                    // if we've crossed into the next row, then we can just stop
                    // iterating
                    return;
                }

            } while (map.next(readkey, readval));   // zeng: 下一个cell
        }
    }

    MapFile.Reader[] getReaders() {
        return this.readers.values().toArray(new MapFile.Reader[this.readers.size()]);
    }

    // zeng: TODO

    /**
     * Get the value for the indicated HStoreKey.  Grab the target value and the
     * previous 'numVersions-1' values, as well.
     * <p>
     * If 'numVersions' is negative, the method returns all available versions.
     *
     * @param key
     * @param numVersions Number of versions to fetch.  Must be > 0.
     * @return values for the specified versions
     * @throws IOException
     */
    byte[][] get(HStoreKey key, int numVersions) throws IOException {
        if (numVersions <= 0) {
            throw new IllegalArgumentException("Number of versions must be > 0");
        }

        this.lock.readLock().lock();
        try {
            // zeng: 先从memcache中获取
            // Check the memcache
            List<byte[]> results = this.memcache.get(key, numVersions);
            // If we got sufficient versions from memcache, return.
            if (results.size() == numVersions) {
                return ImmutableBytesWritable.toArray(results);
            }

            // Keep a list of deleted cell keys.  We need this because as we go through
            // the store files, the cell with the delete marker may be in one file and
            // the old non-delete cell value in a later store file. If we don't keep
            // around the fact that the cell was deleted in a newer record, we end up
            // returning the old value if user is asking for more than one version.
            // This List of deletes should not large since we are only keeping rows
            // and columns that match those set on the scanner and which have delete
            // values.  If memory usage becomes an issue, could redo as bloom filter.
            Map<Text, List<Long>> deletes = new HashMap<Text, List<Long>>();

            // This code below is very close to the body of the getKeys method.

            // zeng: region下的所有hfile reader
            MapFile.Reader[] maparray = getReaders();

            for (int i = maparray.length - 1; i >= 0; i--) {
                MapFile.Reader map = maparray[i];

                synchronized (map) {
                    map.reset();

                    ImmutableBytesWritable readval = new ImmutableBytesWritable();

                    // zeng: 获取比key大(时间戳小)的最相近的key, 把值读入readval
                    HStoreKey readkey = (HStoreKey) map.getClosest(key, readval);

                    if (readkey == null) {
                        // map.getClosest returns null if the passed key is > than the
                        // last key in the map file.  getClosest is a bit of a misnomer
                        // since it returns exact match or the next closest key AFTER not
                        // BEFORE.
                        continue;
                    }

                    // zeng: row column 不相等
                    if (!readkey.matchesRowCol(key)) {
                        continue;
                    }

                    if (!isDeleted(readkey, readval.get(), true, deletes)) {    // zeng: 是否是删除的value
                        results.add(readval.get());
                        // Perhaps only one version is wanted.  I could let this
                        // test happen later in the for loop test but it would cost
                        // the allocation of an ImmutableBytesWritable.
                        if (hasEnoughVersions(numVersions, results)) {  // zeng: 是否足够版本
                            break;
                        }
                    }

                    for (
                            readval = new ImmutableBytesWritable();
                            map.next(readkey, readval) && readkey.matchesRowCol(key) && !hasEnoughVersions(numVersions, results);   // zeng: 这个hfile还能不能读到更多版本的value
                            readval = new ImmutableBytesWritable()
                    ) {
                        if (!isDeleted(readkey, readval.get(), true, deletes)) {    // zeng: 是否是删除的value
                            results.add(readval.get());
                        }
                    }

                }

                // zeng: 是否有足够版本的value
                if (hasEnoughVersions(numVersions, results)) {
                    break;
                }
            }
            return results.size() == 0 ? null : ImmutableBytesWritable.toArray(results);
        } finally {
            this.lock.readLock().unlock();
        }
    }

    private boolean hasEnoughVersions(final int numVersions,
                                      final List<byte[]> results) {
        return numVersions > 0 && results.size() >= numVersions;
    }

    /**
     * Get <code>versions</code> keys matching the origin key's
     * row/column/timestamp and those of an older vintage
     * Default access so can be accessed out of {@link HRegionServer}.
     *
     * @param origin   Where to start searching.
     * @param versions How many versions to return. Pass
     *                 {@link HConstants.ALL_VERSIONS} to retrieve all. Versions will include
     *                 size of passed <code>allKeys</code> in its count.
     * @param allKeys  List of keys prepopulated by keys we found in memcache.
     *                 This method returns this passed list with all matching keys found in
     *                 stores appended.
     * @return The passed <code>allKeys</code> with <code>versions</code> of
     * matching keys found in store files appended.
     * @throws IOException
     */
    List<HStoreKey> getKeys(final HStoreKey origin, final int versions) throws IOException {

        List<HStoreKey> keys = this.memcache.getKeys(origin, versions);
        if (versions != ALL_VERSIONS && keys.size() >= versions) {
            return keys;
        }

        // This code below is very close to the body of the get method.
        this.lock.readLock().lock();
        try {
            MapFile.Reader[] maparray = getReaders();
            for (int i = maparray.length - 1; i >= 0; i--) {
                MapFile.Reader map = maparray[i];
                synchronized (map) {
                    map.reset();

                    // do the priming read
                    ImmutableBytesWritable readval = new ImmutableBytesWritable();
                    HStoreKey readkey = (HStoreKey) map.getClosest(origin, readval);
                    if (readkey == null) {
                        // map.getClosest returns null if the passed key is > than the
                        // last key in the map file.  getClosest is a bit of a misnomer
                        // since it returns exact match or the next closest key AFTER not
                        // BEFORE.
                        continue;
                    }

                    do {

                        // if the row matches, we might want this one.
                        if (rowMatches(origin, readkey)) {

                            // if the cell matches, then we definitely want this key.
                            if (cellMatches(origin, readkey)) {

                                // store the key if it isn't deleted or superceeded by what's
                                // in the memcache
                                if (!isDeleted(readkey, readval.get(), false, null) && !keys.contains(readkey)) {
                                    keys.add(new HStoreKey(readkey));

                                    // if we've collected enough versions, then exit the loop.
                                    if (versions != ALL_VERSIONS && keys.size() >= versions) {
                                        break;
                                    }
                                }

                            } else {
                                // the cell doesn't match, but there might be more with different
                                // timestamps, so move to the next key
                                continue;
                            }

                        } else {
                            // the row doesn't match, so we've gone too far.
                            break;
                        }
                    } while (map.next(readkey, readval)); // advance to the next key
                }
            }

            return keys;
        } finally {
            this.lock.readLock().unlock();
        }
    }

    /**
     * Find the key that matches <i>row</i> exactly, or the one that immediately
     * preceeds it. WARNING: Only use this method on a table where writes occur
     * with stricly increasing timestamps. This method assumes this pattern of
     * writes in order to make it reasonably performant.
     */
    Text getRowKeyAtOrBefore(final Text row)
            throws IOException {
        // map of HStoreKeys that are candidates for holding the row key that
        // most closely matches what we're looking for. we'll have to update it
        // deletes found all over the place as we go along before finally reading
        // the best key out of it at the end.
        SortedMap<HStoreKey, Long> candidateKeys = new TreeMap<HStoreKey, Long>();

        // obtain read lock
        this.lock.readLock().lock();

        try {
            MapFile.Reader[] maparray = getReaders();

            // process each store file
            for (int i = maparray.length - 1; i >= 0; i--) {    // zeng: hifle从老到新
                // update the candidate keys from the current map file
                // zeng: 查找这个hfile中 比 参数key 小的最近邻的key
                rowAtOrBeforeFromMapFile(maparray[i], row, candidateKeys);
            }

            // zeng: 查找memcache中 比 参数key 小的最近邻的key
            // finally, check the memcache
            memcache.getRowKeyAtOrBefore(row, candidateKeys);

            // zeng: 最大的key的row
            // return the best key from candidateKeys
            if (!candidateKeys.isEmpty()) {
                return candidateKeys.lastKey().getRow();
            }

            return null;
        } finally {
            this.lock.readLock().unlock();
        }
    }

    // zeng: 查找这个hfile中 比 参数key 小的最近邻的key

    /**
     * Check an individual MapFile for the row at or before a given key
     * and timestamp
     */
    private void rowAtOrBeforeFromMapFile(
            MapFile.Reader map, Text row, SortedMap<HStoreKey, Long> candidateKeys
    ) throws IOException {
        HStoreKey searchKey = null;

        ImmutableBytesWritable readval = new ImmutableBytesWritable();

        HStoreKey readkey = new HStoreKey();

        synchronized (map) {
            // don't bother with the rest of this if the file is empty
            map.reset();

            if (!map.next(readkey, readval)) {
                return;
            }

            // if there aren't any candidate keys yet, we'll do some things slightly
            // different
            if (candidateKeys.isEmpty()) {
                // zeng: timestamp为max value
                searchKey = new HStoreKey(row);

                // if the row we're looking for is past the end of this mapfile, just
                // save time and add the last key to the candidates.
                HStoreKey finalKey = new HStoreKey();

                // zeng: hfile最大一个key
                map.finalKey(finalKey);

                // zeng: 最大的一个key都比row小, 那么这个hfile中就是这个key是比 参数key 小的最近邻的key
                if (finalKey.getRow().compareTo(row) < 0) {
                    candidateKeys.put(stripTimestamp(finalKey), new Long(finalKey.getTimestamp()));
                    return;
                }

                // zeng:  查找这个hfile中 比 参数key 小的最近邻的key
                // seek to the exact row, or the one that would be immediately before it
                readkey = (HStoreKey) map.getClosest(searchKey, readval, true);

                if (readkey == null) {
                    // didn't find anything that would match, so return
                    return;
                }

                do {
                    // if we have an exact match on row, and it's not a delete, save this
                    // as a candidate key
                    if (readkey.getRow().equals(row)) { // zeng: 如果是同一个row
                        if (!HLogEdit.isDeleted(readval.get())) {   // zeng: value不是HBASE::DELETEVAL
                            candidateKeys.put(stripTimestamp(readkey), new Long(readkey.getTimestamp()));
                        }
                    } else if (readkey.getRow().compareTo(row) > 0) {
                        // if the row key we just read is beyond the key we're searching for,
                        // then we're done. return.
                        return;
                    } else {
                        // so, the row key doesn't match, but we haven't gone past the row
                        // we're seeking yet, so this row is a candidate for closest
                        // (assuming that it isn't a delete).

                        if (!HLogEdit.isDeleted(readval.get())) {
                            // zeng:  这个hfile中 比 参数key 小的最近邻的key
                            candidateKeys.put(stripTimestamp(readkey), new Long(readkey.getTimestamp()));
                        }
                    }
                } while (map.next(readkey, readval));   // zeng: 下一个cell

                // arriving here just means that we consumed the whole rest of the map
                // without going "past" the key we're searching for. we can just fall
                // through here.
            } else {
                // if there are already candidate keys, we need to start our search
                // at the earliest possible key so that we can discover any possible
                // deletes for keys between the start and the search key.
                // zeng: 从candidateKeys中最小的row开始查找
                searchKey = new HStoreKey(candidateKeys.firstKey().getRow());

                HStoreKey strippedKey = null;

                // if the row we're looking for is past the end of this mapfile, just
                // save time and add the last key to the candidates.
                HStoreKey finalKey = new HStoreKey();
                map.finalKey(finalKey);

                if (finalKey.getRow().compareTo(searchKey.getRow()) < 0) {
                    strippedKey = stripTimestamp(finalKey);

                    // if the candidate keys has a cell like this one already,
                    // then we might want to update the timestamp we're using on it
                    if (candidateKeys.containsKey(strippedKey)) {
                        long bestCandidateTs = candidateKeys.get(strippedKey).longValue();
                        if (bestCandidateTs < finalKey.getTimestamp()) {
                            candidateKeys.put(strippedKey, new Long(finalKey.getTimestamp()));
                        }
                    } else {
                        // otherwise, this is a new key, so put it up as a candidate
                        candidateKeys.put(strippedKey, new Long(finalKey.getTimestamp()));
                    }

                    return;
                }

                // zeng: 从candidateKeys中最小的row开始查找
                // seek to the exact row, or the one that would be immediately before it
                readkey = (HStoreKey) map.getClosest(searchKey, readval, true);

                if (readkey == null) {
                    // didn't find anything that would match, so return
                    return;
                }

                do {
                    // if we have an exact match on row, and it's not a delete, save this
                    // as a candidate key
                    if (readkey.getRow().equals(row)) {
                        strippedKey = stripTimestamp(readkey);
                        if (!HLogEdit.isDeleted(readval.get())) {
                            candidateKeys.put(strippedKey, new Long(readkey.getTimestamp()));
                        } else {
                            // if the candidate keys contain any that might match by timestamp,
                            // then check for a match and remove it if it's too young to
                            // survive the delete
                            if (candidateKeys.containsKey(strippedKey)) {
                                long bestCandidateTs =
                                        candidateKeys.get(strippedKey).longValue();
                                if (bestCandidateTs <= readkey.getTimestamp()) {
                                    candidateKeys.remove(strippedKey);
                                }
                            }
                        }
                    } else if (readkey.getRow().compareTo(row) > 0) {   // zeng: 直到大于查找的row
                        // if the row key we just read is beyond the key we're searching for,
                        // then we're done. return.
                        return;
                    } else {
                        strippedKey = stripTimestamp(readkey);

                        // so, the row key doesn't match, but we haven't gone past the row
                        // we're seeking yet, so this row is a candidate for closest
                        // (assuming that it isn't a delete).
                        if (!HLogEdit.isDeleted(readval.get())) {
                            // zeng: 更大的key, 或者更大的timestamp
                            candidateKeys.put(strippedKey, readkey.getTimestamp());
                        } else {
                            // if the candidate keys contain any that might match by timestamp,
                            // then check for a match and remove it if it's too young to
                            // survive the delete
                            if (candidateKeys.containsKey(strippedKey)) {
                                long bestCandidateTs = candidateKeys.get(strippedKey).longValue();

                                // zeng: 更大的timestamp
                                if (bestCandidateTs <= readkey.getTimestamp()) {
                                    // zeng: 从candidateKeys中移除
                                    candidateKeys.remove(strippedKey);
                                }
                            }
                        }
                    }
                } while (map.next(readkey, readval));   // zeng: 下一个cell

            }
        }
    }

    static HStoreKey stripTimestamp(HStoreKey key) {
        return new HStoreKey(key.getRow(), key.getColumn());
    }

    /**
     * Test that the <i>target</i> matches the <i>origin</i>. If the
     * <i>origin</i> has an empty column, then it's assumed to mean any column
     * matches and only match on row and timestamp. Otherwise, it compares the
     * keys with HStoreKey.matchesRowCol().
     *
     * @param origin The key we're testing against
     * @param target The key we're testing
     */
    private boolean cellMatches(HStoreKey origin, HStoreKey target) {
        // if the origin's column is empty, then we're matching any column
        if (origin.getColumn().equals(new Text())) {
            // if the row matches, then...
            if (target.getRow().equals(origin.getRow())) {
                // check the timestamp
                return target.getTimestamp() <= origin.getTimestamp();
            }

            return false;
        }

        // otherwise, we want to match on row and column
        return target.matchesRowCol(origin);
    }

    /**
     * Test that the <i>target</i> matches the <i>origin</i>. If the <i>origin</i>
     * has an empty column, then it just tests row equivalence. Otherwise, it uses
     * HStoreKey.matchesRowCol().
     *
     * @param origin Key we're testing against
     * @param target Key we're testing
     */
    private boolean rowMatches(HStoreKey origin, HStoreKey target) {
        // if the origin's column is empty, then we're matching any column
        if (origin.getColumn().equals(new Text())) {
            // if the row matches, then...
            return target.getRow().equals(origin.getRow());
        }
        // otherwise, we want to match on row and column
        return target.matchesRowCol(origin);
    }

    /*
     * Data structure to hold result of a look at store file sizes.
     */
    static class HStoreSize {
        final long aggregate;
        final long largest;
        boolean splitable;

        HStoreSize(final long a, final long l, final boolean s) {
            this.aggregate = a;
            this.largest = l;
            this.splitable = s;
        }

        long getAggregate() {
            return this.aggregate;
        }

        long getLargest() {
            return this.largest;
        }

        boolean isSplitable() {
            return this.splitable;
        }

        void setSplitable(final boolean s) {
            this.splitable = s;
        }
    }

    /**
     * Gets size for the store.
     *
     * @param midKey Gets set to the middle key of the largest splitable store
     *               file or its set to empty if largest is not splitable.
     * @return Sizes for the store and the passed <code>midKey</code> is
     * set to midKey of largest splitable.  Otherwise, its set to empty
     * to indicate we couldn't find a midkey to split on
     */
    HStoreSize size(Text midKey) {
        long maxSize = 0L;
        long aggregateSize = 0L;
        // Not splitable if we find a reference store file present in the store.
        boolean splitable = true;
        if (this.storefiles.size() <= 0) {
            return new HStoreSize(0, 0, splitable);
        }

        this.lock.readLock().lock();
        try {
            Long mapIndex = Long.valueOf(0L);
            // Iterate through all the MapFiles
            for (Map.Entry<Long, HStoreFile> e : storefiles.entrySet()) {
                HStoreFile curHSF = e.getValue();
                long size = curHSF.length();
                aggregateSize += size;
                if (maxSize == 0L || size > maxSize) {
                    // This is the largest one so far
                    maxSize = size;
                    mapIndex = e.getKey();
                }
                if (splitable) {
                    splitable = !curHSF.isReference();
                }
            }
            if (splitable) {
                MapFile.Reader r = this.readers.get(mapIndex);
                // seek back to the beginning of mapfile
                r.reset();
                // get the first and last keys
                HStoreKey firstKey = new HStoreKey();
                HStoreKey lastKey = new HStoreKey();
                Writable value = new ImmutableBytesWritable();
                r.next(firstKey, value);
                r.finalKey(lastKey);
                // get the midkey
                HStoreKey mk = (HStoreKey) r.midKey();
                if (mk != null) {
                    // if the midkey is the same as the first and last keys, then we cannot
                    // (ever) split this region.
                    if (mk.getRow().equals(firstKey.getRow()) &&
                            mk.getRow().equals(lastKey.getRow())) {
                        return new HStoreSize(aggregateSize, maxSize, false);
                    }
                    // Otherwise, set midKey
                    midKey.set(mk.getRow());
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed getting store size for " + this.storeName, e);
        } finally {
            this.lock.readLock().unlock();
        }
        return new HStoreSize(aggregateSize, maxSize, splitable);
    }

    //////////////////////////////////////////////////////////////////////////////
    // File administration
    //////////////////////////////////////////////////////////////////////////////

    /**
     * Return a scanner for both the memcache and the HStore files
     */
    HInternalScannerInterface getScanner(
            long timestamp, Text targetCols[], Text firstRow, RowFilterInterface filter
    ) throws IOException {

        newScannerLock.readLock().lock();           // ability to create a new
        // scanner during a compaction
        try {
            lock.readLock().lock();                   // lock HStore
            try {
                // zeng: new HStoreScanner
                return new HStoreScanner(targetCols, firstRow, timestamp, filter);
            } finally {
                lock.readLock().unlock();
            }
        } finally {
            newScannerLock.readLock().unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return this.storeName;
    }

    /*
     * @see writeSplitInfo(Path p, HStoreFile hsf, FileSystem fs)
     */
    static HStoreFile.Reference readSplitInfo(final Path p, final FileSystem fs)
            throws IOException {
        FSDataInputStream in = fs.open(p);
        try {
            HStoreFile.Reference r = new HStoreFile.Reference();
            r.readFields(in);
            return r;
        } finally {
            in.close();
        }
    }

    /**
     * @param p Path to check.
     * @return True if the path has format of a HStoreFile reference.
     */
    public static boolean isReference(final Path p) {
        return isReference(p, REF_NAME_PARSER.matcher(p.getName()));
    }

    private static boolean isReference(final Path p, final Matcher m) {
        if (m == null || !m.matches()) {
            LOG.warn("Failed match of store file name " + p.toString());
            throw new RuntimeException("Failed match of store file name " +
                    p.toString());
        }
        return m.groupCount() > 1 && m.group(2) != null;
    }

    /**
     * A scanner that iterates through the HStore files
     */
    private class StoreFileScanner extends HAbstractScanner {
        @SuppressWarnings("hiding")
        private MapFile.Reader[] readers;

        StoreFileScanner(long timestamp, Text[] targetCols, Text firstRow)
                throws IOException {
            // zeng: 每个family下有一个 ColumnMatch 列表
            super(timestamp, targetCols);

            try {
                this.readers = new MapFile.Reader[storefiles.size()];

                // zeng: hfile 从老到新
                // Most recent map file should be first
                int i = readers.length - 1;
                for (HStoreFile curHSF : storefiles.values()) {
                    readers[i--] = curHSF.getReader(fs, bloomFilter);
                }

                // zeng: 每个reader一个keys中的位置
                this.keys = new HStoreKey[readers.length];
                // zeng: 每个reader一个vals中的位置
                this.vals = new byte[readers.length][];

                // Advance the readers to the first pos.
                for (i = 0; i < readers.length; i++) {  // zeng: 所有reader
                    keys[i] = new HStoreKey();

                    if (firstRow.getLength() != 0) {
                        if (findFirstRow(i, firstRow)) {    // zeng: 直到找到比firstRow的key, 设置进keys[i]
                            continue;
                        }
                    }

                    // zeng: 执行到这里, 说明findFirstRow没匹配到适当的column, 继续往下找

                    while (getNext(i)) {    // zeng: 下一个cell
                        if (columnMatch(i)) {   // zeng: 直到匹配对应的full column
                            break;
                        }
                    }
                }

            } catch (Exception ex) {
                close();
                IOException e = new IOException("HStoreScanner failed construction");
                e.initCause(ex);
                throw e;
            }
        }

        /**
         * The user didn't want to start scanning at the first row. This method
         * seeks to the requested row.
         *
         * @param i        - which iterator to advance
         * @param firstRow - seek to this row
         * @return - true if this is the first row or if the row was not found
         */
        @Override
        boolean findFirstRow(int i, Text firstRow) throws IOException {
            ImmutableBytesWritable ibw = new ImmutableBytesWritable();

            // zeng: 找到比 firstRow 大的key
            HStoreKey firstKey = (HStoreKey) readers[i].getClosest(new HStoreKey(firstRow), ibw);
            if (firstKey == null) {
                // Didn't find it. Close the scanner and return TRUE
                closeSubScanner(i);
                return true;
            }

            this.vals[i] = ibw.get();
            keys[i].setRow(firstKey.getRow());
            keys[i].setColumn(firstKey.getColumn());
            keys[i].setVersion(firstKey.getTimestamp());

            // zeng: 是否匹配 full column
            return columnMatch(i);
        }

        /**
         * Get the next value from the specified reader.
         *
         * @param i - which reader to fetch next value from
         * @return - true if there is more data available
         */
        @Override
        boolean getNext(int i) throws IOException {
            boolean result = false;

            ImmutableBytesWritable ibw = new ImmutableBytesWritable();

            while (true) {
                if (!readers[i].next(keys[i], ibw)) {   // zeng: 下一个cell
                    closeSubScanner(i);
                    break;
                }

                if (keys[i].getTimestamp() <= this.timestamp) { // zeng: timestamp 小于等于 参数timestamp
                    vals[i] = ibw.get();
                    result = true;
                    break;
                }
            }

            return result;
        }

        /**
         * Close down the indicated reader.
         */
        @Override
        void closeSubScanner(int i) {
            try {
                if (readers[i] != null) {
                    try {
                        readers[i].close();
                    } catch (IOException e) {
                        LOG.error(storeName + " closing sub-scanner", e);
                    }
                }

            } finally {
                readers[i] = null;
                keys[i] = null;
                vals[i] = null;
            }
        }

        /**
         * Shut it down!
         */
        public void close() {
            if (!scannerClosed) {
                try {
                    for (int i = 0; i < readers.length; i++) {
                        if (readers[i] != null) {
                            try {
                                readers[i].close();
                            } catch (IOException e) {
                                LOG.error(storeName + " closing scanner", e);
                            }
                        }
                    }

                } finally {
                    scannerClosed = true;
                }
            }
        }
    }

    /**
     * Scanner scans both the memcache and the HStore
     */
    private class HStoreScanner implements HInternalScannerInterface {
        private HInternalScannerInterface[] scanners;
        private TreeMap<Text, byte[]>[] resultSets;
        private HStoreKey[] keys;
        private boolean wildcardMatch = false;
        private boolean multipleMatchers = false;
        private RowFilterInterface dataFilter;

        /**
         * Create an Scanner with a handle on the memcache and HStore files.
         */
        @SuppressWarnings("unchecked")
        HStoreScanner(
                Text[] targetCols, Text firstRow, long timestamp, RowFilterInterface filter
        ) throws IOException {

            this.dataFilter = filter;
            if (null != dataFilter) {
                dataFilter.reset();
            }

            this.scanners = new HInternalScannerInterface[2];
            this.resultSets = new TreeMap[scanners.length];
            this.keys = new HStoreKey[scanners.length];

            try {
                // zeng: memcache scanner
                scanners[0] = memcache.getScanner(timestamp, targetCols, firstRow);
                // zeng: hfile scanner
                scanners[1] = new StoreFileScanner(timestamp, targetCols, firstRow);

                for (int i = 0; i < scanners.length; i++) {
                    if (scanners[i].isWildcardScanner()) {
                        this.wildcardMatch = true;
                    }
                    if (scanners[i].isMultipleMatchScanner()) {
                        this.multipleMatchers = true;
                    }
                }

            } catch (IOException e) {
                for (int i = 0; i < this.scanners.length; i++) {
                    if (scanners[i] != null) {
                        closeScanner(i);
                    }
                }
                throw e;
            }

            // Advance to the first key in each scanner.
            // All results will match the required column-set and scanTime.

            for (int i = 0; i < scanners.length; i++) { // zeng: 遍历memcache 与 hfile scanner

                keys[i] = new HStoreKey();
                resultSets[i] = new TreeMap<Text, byte[]>();

                if (scanners[i] != null && !scanners[i].next(keys[i], resultSets[i])) { // zeng: MemcacheScanner.next StoreFileScanner.next
                    closeScanner(i);
                }

            }

            // As we have now successfully completed initialization, increment the
            // activeScanner count.
            activeScanners.incrementAndGet();
        }

        /**
         * @return true if the scanner is a wild card scanner
         */
        public boolean isWildcardScanner() {
            return wildcardMatch;
        }

        /**
         * @return true if the scanner is a multiple match scanner
         */
        public boolean isMultipleMatchScanner() {
            return multipleMatchers;
        }

        /**
         * {@inheritDoc}
         */
        public boolean next(HStoreKey key, SortedMap<Text, byte[]> results) throws IOException {

            // Filtered flag is set by filters.  If a cell has been 'filtered out'
            // -- i.e. it is not to be returned to the caller -- the flag is 'true'.
            boolean filtered = true;
            boolean moreToFollow = true;

            while (filtered && moreToFollow) {  // zeng: 如果行被过滤了 且 moreToFollow, 那么继续下一行(否则只处理一行)

                // Find the lowest-possible key.
                // zeng: keys中最小的key
                Text chosenRow = null;
                long chosenTimestamp = -1;
                for (int i = 0; i < this.keys.length; i++) {

                    if (
                            scanners[i] != null && (
                                    chosenRow == null || keys[i].getRow().compareTo(chosenRow) < 0 || (
                                            keys[i].getRow().compareTo(chosenRow) == 0 && keys[i].getTimestamp() > chosenTimestamp
                                    )
                            )
                    ) {

                        chosenRow = new Text(keys[i].getRow());
                        chosenTimestamp = keys[i].getTimestamp();


                    }
                }

                // Filter whole row by row key?
                // zeng: 这一行是否被过滤了
                filtered = dataFilter != null ? dataFilter.filter(chosenRow) : false;

                // Store the key and results for each sub-scanner. Merge them as
                // appropriate.
                if (chosenTimestamp >= 0 && !filtered) {
                    // Here we are setting the passed in key with current row+timestamp
                    // zeng: 写入key
                    key.setRow(chosenRow);
                    // zeng: 用这一行符合的数据的最小的时间戳
                    key.setVersion(chosenTimestamp);
                    key.setColumn(HConstants.EMPTY_TEXT);

                    // Keep list of deleted cell keys within this row.  We need this
                    // because as we go through scanners, the delete record may be in an
                    // early scanner and then the same record with a non-delete, non-null
                    // value in a later. Without history of what we've seen, we'll return
                    // deleted values. This List should not ever grow too large since we
                    // are only keeping rows and columns that match those set on the
                    // scanner and which have delete values.  If memory usage becomes a
                    // problem, could redo as bloom filter.
                    List<HStoreKey> deletes = new ArrayList<HStoreKey>();

                    for (int i = 0; i < scanners.length && !filtered; i++) {

                        while (scanners[i] != null && !filtered && moreToFollow && keys[i].getRow().compareTo(chosenRow) == 0) {    // zeng: 直到扫完一行的所有版本

                            // If we are doing a wild card match or there are multiple
                            // matchers per column, we need to scan all the older versions of
                            // this row to pick up the rest of the family members
                            // zeng: 是否只要一列
                            if (!wildcardMatch && !multipleMatchers && keys[i].getTimestamp() != chosenTimestamp) {
                                break;
                            }

                            // NOTE: We used to do results.putAll(resultSets[i]);
                            // but this had the effect of overwriting newer
                            // values with older ones. So now we only insert
                            // a result if the map does not contain the key.
                            // zeng: 遍历resultSets
                            HStoreKey hsk = new HStoreKey(key.getRow(), EMPTY_TEXT, key.getTimestamp());
                            for (Map.Entry<Text, byte[]> e : resultSets[i].entrySet()) {

                                hsk.setColumn(e.getKey());

                                if (HLogEdit.isDeleted(e.getValue())) { // zeng: tombstone

                                    if (!deletes.contains(hsk)) {
                                        // Key changes as we cycle the for loop so add a copy to
                                        // the set of deletes.
                                        deletes.add(new HStoreKey(hsk));
                                    }

                                } else if (!deletes.contains(hsk) && !filtered && moreToFollow && !results.containsKey(e.getKey())) {   // zeng: 如果没有对应tombstone

                                    if (dataFilter != null) {
                                        // Filter whole row by column data?
                                        // zeng: 根据 列名 做 行过滤
                                        filtered = dataFilter.filter(chosenRow, e.getKey(), e.getValue());
                                        if (filtered) {
                                            results.clear();
                                            break;
                                        }
                                    }

                                    // zeng: column -> value 写入 results
                                    results.put(e.getKey(), e.getValue());

                                }

                            }

                            // zeng: reset resultSets
                            resultSets[i].clear();

                            // zeng: 下一`行-timestamp`
                            if (!scanners[i].next(keys[i], resultSets[i])) {
                                closeScanner(i);
                            }

                        }

                    }

                }

                // zeng: 下一行
                for (int i = 0; i < scanners.length; i++) {

                    // If the current scanner is non-null AND has a lower-or-equal
                    // row label, then its timestamp is bad. We need to advance it.
                    while (scanners[i] != null && keys[i].getRow().compareTo(chosenRow) <= 0) {

                        resultSets[i].clear();

                        if (!scanners[i].next(keys[i], resultSets[i])) {    // zeng: 下一`行-timestamp`
                            closeScanner(i);
                        }

                    }

                }

                // zeng: 是否有获得key
                moreToFollow = chosenTimestamp >= 0;


                if (dataFilter != null) {

                    // zeng: 这一行处理过了
                    if (moreToFollow) {
                        dataFilter.rowProcessed(filtered, chosenRow);
                    }

                    // zeng: 是否过滤掉剩下所有行
                    if (dataFilter.filterAllRemaining()) {
                        moreToFollow = false;
                    }

                }

                // zeng: 这一行没有符合的数据, 下一行
                if (results.size() <= 0 && !filtered) {
                    // There were no results found for this row.  Marked it as
                    // 'filtered'-out otherwise we will not move on to the next row.
                    filtered = true;
                }
            }

            // If we got no results, then there is no more to follow.
            if (results == null || results.size() <= 0) {
                moreToFollow = false;
            }

            // Make sure scanners closed if no more results
            if (!moreToFollow) {

                for (int i = 0; i < scanners.length; i++) {

                    if (null != scanners[i]) {
                        closeScanner(i);
                    }

                }

            }

            return moreToFollow;
        }


        /**
         * Shut down a single scanner
         */
        void closeScanner(int i) {
            try {
                try {
                    scanners[i].close();
                } catch (IOException e) {
                    LOG.warn(storeName + " failed closing scanner " + i, e);
                }
            } finally {
                scanners[i] = null;
                keys[i] = null;
                resultSets[i] = null;
            }
        }

        /**
         * {@inheritDoc}
         */
        public void close() {
            try {
                for (int i = 0; i < scanners.length; i++) {
                    if (scanners[i] != null) {
                        closeScanner(i);
                    }
                }
            } finally {
                synchronized (activeScanners) {
                    int numberOfScanners = activeScanners.decrementAndGet();
                    if (numberOfScanners < 0) {
                        LOG.error(storeName +
                                " number of active scanners less than zero: " +
                                numberOfScanners + " resetting to zero");
                        activeScanners.set(0);
                        numberOfScanners = 0;
                    }
                    if (numberOfScanners == 0) {
                        activeScanners.notifyAll();
                    }
                }

            }
        }

        /**
         * {@inheritDoc}
         */
        public Iterator<Entry<HStoreKey, SortedMap<Text, byte[]>>> iterator() {
            throw new UnsupportedOperationException("Unimplemented serverside. " +
                    "next(HStoreKey, StortedMap(...) is more efficient");
        }
    }
}
