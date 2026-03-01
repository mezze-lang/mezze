package com.mezze.interpreter.v2.nodes.builtin.collections;

import com.mezze.interpreter.v2.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Native Map (HashMap) operations - ULTRA PERFORMANT.
 *
 * Map in V2 uses LinkedHashMap for predictable iteration order.
 * All operations are immutable (return new maps).
 */
public final class MapNatives {

    private MapNatives() {}

    /**
     * Wrapper class for Map
     */
    public static final class MezzeMap {
        private final LinkedHashMap<Object, Object> map;

        public MezzeMap() {
            this.map = new LinkedHashMap<>();
        }

        private MezzeMap(LinkedHashMap<Object, Object> map) {
            this.map = map;
        }

        public LinkedHashMap<Object, Object> getMap() {
            return map;
        }

        public int size() {
            return map.size();
        }

        public boolean isEmpty() {
            return map.isEmpty();
        }

        @TruffleBoundary
        public boolean containsKey(Object key) {
            return map.containsKey(key);
        }

        @TruffleBoundary
        public MezzeVariant get(Object key) {
            Object value = map.get(key);
            if (value == null && !map.containsKey(key)) {
                return MezzeVariant.none();
            }
            return MezzeVariant.some(value);
        }

        @TruffleBoundary
        public MezzeMap insert(Object key, Object value) {
            LinkedHashMap<Object, Object> newMap = new LinkedHashMap<>(map);
            newMap.put(key, value);
            return new MezzeMap(newMap);
        }

        @TruffleBoundary
        public MezzeMap remove(Object key) {
            LinkedHashMap<Object, Object> newMap = new LinkedHashMap<>(map);
            newMap.remove(key);
            return new MezzeMap(newMap);
        }

        @TruffleBoundary
        public Object[] keys() {
            return map.keySet().toArray();
        }

        @TruffleBoundary
        public Object[] values() {
            return map.values().toArray();
        }

        @TruffleBoundary
        public Object[] entries() {
            Object[] result = new Object[map.size()];
            int i = 0;
            for (Map.Entry<Object, Object> entry : map.entrySet()) {
                result[i++] = MezzeRecord.fromFields(
                    new String[]{"key", "value"},
                    new Object[]{entry.getKey(), entry.getValue()}
                );
            }
            return result;
        }
    }

    // ==================== Factory Operations ====================

    public static MezzeMap empty() {
        return new MezzeMap();
    }

    @TruffleBoundary
    public static MezzeMap fromKeys(Object[] keys, MezzeFunction valueFn,
                                                                          com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
        for (Object key : keys) {
            Object value = callNode.call(valueFn.getCallTarget(),
                prepareArgs(valueFn, key));
            map.put(key, value);
        }
        return new MezzeMap(map);
    }

    // ==================== Basic Operations ====================

    public static long size(MezzeMap map) {
        return map.size();
    }

    public static boolean isEmpty(MezzeMap map) {
        return map.isEmpty();
    }

    public static boolean containsKey(MezzeMap map, Object key) {
        return map.containsKey(key);
    }

    public static MezzeVariant get(MezzeMap map, Object key) {
        return map.get(key);
    }

    public static MezzeMap insert(MezzeMap map, Object key, Object value) {
        return map.insert(key, value);
    }

    public static MezzeMap remove(MezzeMap map, Object key) {
        return map.remove(key);
    }

    @TruffleBoundary
    public static MezzeMap removeAll(MezzeMap map, Object[] keys) {
        LinkedHashMap<Object, Object> newMap = new LinkedHashMap<>(map.getMap());
        for (Object key : keys) {
            newMap.remove(key);
        }
        return new MezzeMap(newMap);
    }

    public static Object[] keys(MezzeMap map) {
        return map.keys();
    }

    public static Object[] values(MezzeMap map) {
        return map.values();
    }

    public static Object[] entries(MezzeMap map) {
        return map.entries();
    }

    // ==================== Transform Operations ====================

    @TruffleBoundary
    public static MezzeMap filter(MezzeMap map, MezzeFunction predicate,
                                                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        LinkedHashMap<Object, Object> result = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            MezzeRecord kvRecord = MezzeRecord.fromFields(
                new String[]{"key", "value"},
                new Object[]{entry.getKey(), entry.getValue()}
            );
            Object res = callNode.call(predicate.getCallTarget(),
                prepareArgs(predicate, kvRecord));
            if (res instanceof Boolean && (Boolean) res) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return new MezzeMap(result);
    }

    @TruffleBoundary
    public static MezzeMap mapValues(MezzeMap map, MezzeFunction mapper,
                                                                            com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        LinkedHashMap<Object, Object> result = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            Object newValue = callNode.call(mapper.getCallTarget(),
                prepareArgs(mapper, entry.getValue()));
            result.put(entry.getKey(), newValue);
        }
        return new MezzeMap(result);
    }

    @TruffleBoundary
    public static Object fold(MezzeMap map, Object init, MezzeFunction folder,
                                                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object acc = init;
        for (Map.Entry<Object, Object> entry : map.getMap().entrySet()) {
            MezzeRecord kvRecord = MezzeRecord.fromFields(
                new String[]{"key", "value"},
                new Object[]{entry.getKey(), entry.getValue()}
            );
            acc = callNode.call(folder.getCallTarget(),
                prepareArgsTwo(folder, acc, kvRecord));
        }
        return acc;
    }

    // ==================== Merge Operations ====================

    @TruffleBoundary
    public static MezzeMap merge(MezzeMap map1, MezzeMap map2) {
        LinkedHashMap<Object, Object> result = new LinkedHashMap<>(map1.getMap());
        result.putAll(map2.getMap());
        return new MezzeMap(result);
    }

    @TruffleBoundary
    public static MezzeMap mergeWith(MezzeMap map1, MezzeMap map2, MezzeFunction merger,
                                                                            com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        LinkedHashMap<Object, Object> result = new LinkedHashMap<>(map1.getMap());
        for (Map.Entry<Object, Object> entry : map2.getMap().entrySet()) {
            Object key = entry.getKey();
            Object newValue = entry.getValue();
            if (result.containsKey(key)) {
                Object oldValue = result.get(key);
                newValue = callNode.call(merger.getCallTarget(),
                    prepareArgsTwo(merger, oldValue, newValue));
            }
            result.put(key, newValue);
        }
        return new MezzeMap(result);
    }

    // ==================== Equality ====================

    @TruffleBoundary
    public static boolean equals(MezzeMap map1, MezzeMap map2) {
        return map1.getMap().equals(map2.getMap());
    }

    // ==================== Helper Methods ====================

    private static Object[] prepareArgs(MezzeFunction fn, Object arg) {
        Object[] closureValues = fn.getClosureValues();
        Object[] args = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, args, 0, closureValues.length);
        args[closureValues.length] = MezzeRecord.fromFields(
            new String[]{"x"}, new Object[]{arg}
        );
        return args;
    }

    private static Object[] prepareArgsTwo(MezzeFunction fn, Object arg1, Object arg2) {
        Object[] closureValues = fn.getClosureValues();
        Object[] args = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, args, 0, closureValues.length);
        args[closureValues.length] = MezzeRecord.fromFields(
            new String[]{"acc", "x"}, new Object[]{arg1, arg2}
        );
        return args;
    }
}
