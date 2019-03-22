package net.marvk.chess.kairukuengine;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

public class TranspositionTable {
    private final LinkedHashMap<Long, Entry> table;
    private final int capacity;

    public TranspositionTable(final int capacity) {
        this.capacity = capacity;
        this.table = new LinkedHashMapWithCapacity(this.capacity);
    }

    public TranspositionTable() {
        this.capacity = -1;
        this.table = new LinkedHashMap<>();
    }

    public Entry get(final long hash) {
        return table.get(hash);
    }

    private static int putCount = 0;

    public Entry put(final long key, final Entry value) {
        putCount++;
        if (putCount % 100000 == 0) {
            System.out.println("putCount = " + putCount);
        }
        return table.put(key, value);
    }

    public void clear() {
        table.clear();
    }

    @AllArgsConstructor
    @Data
    public static class Entry {
        private final ValuedMove valuedMove;
        private int depth;
        private int value;
        private NodeType nodeType;
    }

    public enum NodeType {
        EXACT,
        LOWERBOUND,
        UPPERBOUND
    }

    public double load() {
        if (capacity <= 0) {
            return 0.;
        }

        return ((double) table.size()) / capacity;
    }

    public int size() {
        return table.size();
    }

    private static class LinkedHashMapWithCapacity extends LinkedHashMap<Long, Entry> {
        private final int capacity;

        public LinkedHashMapWithCapacity(final int capacity) {
            super();
            this.capacity = capacity;
        }

        @Override
        protected boolean removeEldestEntry(final Map.Entry<Long, Entry> eldest) {
            return size() > capacity;
        }
    }
}
