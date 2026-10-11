package com.mrgregles.bsp_region_guard.storage;

import java.util.List;
import java.util.Map;

/** Where the mirror keeps rows: MySQL in production, memory in tests. Called from the mirror's worker thread only. */
public interface MirrorBackend extends AutoCloseable {
    /** One region as stored: {@code data} is the region's NBT, gzipped; a deleted row is a tombstone. */
    record RegionRow(String server, String dimension, String id, int version, long updatedAt, byte[] data, boolean deleted) {
    }

    /** One cross-server message. */
    record Message(long id, long createdAt, String source, String kind, String dimension, String regionId, String payload) {
    }

    void init() throws Exception;

    void upsertRegion(RegionRow row) throws Exception;

    void markDeleted(String server, String dimension, String id, long when) throws Exception;

    List<RegionRow> fetchRegions(String server, String dimension) throws Exception;

    /** Non-deleted region counts per server id, for the status command. */
    Map<String, Integer> countByServer() throws Exception;

    long publish(Message message) throws Exception;

    List<Message> fetchMessagesAfter(long id, int limit) throws Exception;

    long latestMessageId() throws Exception;

    String describe();

    @Override
    void close() throws Exception;
}
