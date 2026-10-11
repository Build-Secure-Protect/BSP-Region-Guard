package com.mrgregles.bsp_region_guard.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** An in-memory backend: for GameTests, and shared between two mirrors to play two servers. */
public final class MemoryBackend implements MirrorBackend {
    private final Map<String, RegionRow> regions = new LinkedHashMap<>();
    private final List<Message> messages = new ArrayList<>();

    private static String key(String server, String dimension, String id) {
        return server + "|" + dimension + "|" + id;
    }

    @Override
    public synchronized void init() {
    }

    @Override
    public synchronized void upsertRegion(RegionRow row) {
        regions.put(key(row.server(), row.dimension(), row.id()), row);
    }

    @Override
    public synchronized void markDeleted(String server, String dimension, String id, long when) {
        RegionRow old = regions.get(key(server, dimension, id));
        regions.put(key(server, dimension, id), new RegionRow(server, dimension, id, old == null ? 0 : old.version() + 1, when, new byte[0], true));
    }

    @Override
    public synchronized List<RegionRow> fetchRegions(String server, String dimension) {
        List<RegionRow> out = new ArrayList<>();
        for (RegionRow r : regions.values()) {
            if (r.server().equals(server) && r.dimension().equals(dimension)) {
                out.add(r);
            }
        }
        return out;
    }

    @Override
    public synchronized Map<String, Integer> countByServer() {
        Map<String, Integer> out = new TreeMap<>();
        for (RegionRow r : regions.values()) {
            if (!r.deleted()) {
                out.merge(r.server(), 1, Integer::sum);
            }
        }
        return out;
    }

    @Override
    public synchronized long publish(Message message) {
        long id = messages.size() + 1;
        messages.add(new Message(id, message.createdAt(), message.source(), message.kind(), message.dimension(), message.regionId(), message.payload()));
        return id;
    }

    @Override
    public synchronized List<Message> fetchMessagesAfter(long id, int limit) {
        List<Message> out = new ArrayList<>();
        for (Message m : messages) {
            if (m.id() > id && out.size() < limit) {
                out.add(m);
            }
        }
        return out;
    }

    @Override
    public synchronized long latestMessageId() {
        return messages.size();
    }

    @Override
    public String describe() {
        return "memory";
    }

    @Override
    public void close() {
    }

    /** For tests. */
    public synchronized int rows() {
        return regions.size();
    }
}
