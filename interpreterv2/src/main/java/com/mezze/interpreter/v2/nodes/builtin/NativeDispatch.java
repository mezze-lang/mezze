package com.mezze.interpreter.v2.nodes.builtin;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.nodes.builtin.collections.*;
import com.mezze.interpreter.v2.nodes.builtin.concurrency.*;
import com.mezze.interpreter.v2.nodes.builtin.effects.*;
import com.mezze.interpreter.v2.nodes.builtin.io.*;
import com.mezze.interpreter.v2.nodes.builtin.primitives.*;
import com.mezze.interpreter.v2.values.*;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;

import java.util.HashMap;
import java.util.Map;

/**
 * Comprehensive Native function dispatch - AGGRESSIVELY OPTIMIZED.
 *
 * Key optimizations:
 * 1. Cached handler lookup - O(1) after first call, no hash computation
 * 2. @TruffleBoundary only for complex handlers (HOFs, etc.)
 * 3. Direct handler invocation via cached reference
 *
 * This dispatcher handles ALL native functions by delegating to specialized
 * native modules.
 */
@NodeInfo(shortName = "native-dispatch-v2")
public final class NativeDispatch extends MezzeNode {

    @CompilationFinal
    private final String functionName;

    @Child
    private MezzeNode argumentNode;

    @Child
    private IndirectCallNode callNode;

    // Cached handler - looked up once, then direct call
    @CompilationFinal
    private NativeHandler cachedHandler;

    @FunctionalInterface
    interface NativeHandler {
        Object handle(MezzeRecord args, IndirectCallNode callNode);
    }

    /**
     * Extended handler interface that receives the node for context access.
     * Used by async handlers that need TruffleLanguage.Env for thread creation.
     */
    @FunctionalInterface
    interface NativeHandlerWithNode {
        Object handle(MezzeRecord args, IndirectCallNode callNode, MezzeNode node);
    }

    // Static dispatch tables - initialized once
    private static final Map<String, NativeHandler> HANDLERS = new HashMap<>();
    private static final Map<String, NativeHandlerWithNode> HANDLERS_WITH_NODE = new HashMap<>();

    static {
        initHandlers();
    }

    // Cached handler with node access
    @CompilationFinal
    private NativeHandlerWithNode cachedHandlerWithNode;

    public NativeDispatch(String functionName, MezzeNode argumentNode) {
        this.functionName = functionName;
        this.argumentNode = argumentNode;
        this.callNode = IndirectCallNode.create();
        // Pre-cache the handler at construction time
        this.cachedHandler = HANDLERS.get(functionName);
        this.cachedHandlerWithNode = HANDLERS_WITH_NODE.get(functionName);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object arg = argumentNode.execute(frame);

        if (!(arg instanceof MezzeRecord record)) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException(functionName + " expects record argument, got: " +
                (arg == null ? "null" : arg.getClass().getName()));
        }

        // FAST PATH: Check handlers with node access first (for async operations)
        NativeHandlerWithNode handlerWithNode = cachedHandlerWithNode;
        if (handlerWithNode != null) {
            return handlerWithNode.handle(record, callNode, this);
        }

        // FAST PATH: Direct handler invocation via cached reference
        // No hash lookup, no @TruffleBoundary overhead for simple handlers
        NativeHandler handler = cachedHandler;
        if (handler != null) {
            return handler.handle(record, callNode);
        }

        // Slow path: unknown function
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException("Unknown native function: " + functionName);
    }

    public static NativeDispatch create(String functionName, MezzeNode argumentNode) {
        return new NativeDispatch(functionName, argumentNode);
    }

    /**
     * Check if a function name is a known native function.
     * Called at parse time, not in hot path.
     */
    public static boolean isNativeFunction(String name) {
        return name.startsWith("__native_") && (HANDLERS.containsKey(name) || HANDLERS_WITH_NODE.containsKey(name));
    }

    // ==================== Handler Registration ====================

    private static void initHandlers() {
        // ArrayList operations
        registerArrayListHandlers();
        // List operations
        registerListHandlers();
        // Set operations
        registerSetHandlers();
        // SortedSet operations
        registerSortedSetHandlers();
        // Map operations
        registerMapHandlers();
        // SortedMap operations
        registerSortedMapHandlers();
        // Deque operations
        registerDequeHandlers();
        // NonEmptyList operations
        registerNonEmptyListHandlers();
        // Nat operations
        registerNatHandlers();
        // Char operations
        registerCharHandlers();
        // Frac operations
        registerFracHandlers();
        // Bytes operations
        registerBytesHandlers();
        // Time operations
        registerTimeHandlers();
        // String case conversion operations
        registerStringCaseHandlers();
        // Float additional operations
        registerFloatHandlers();
        // Log operations
        registerLogHandlers();
        // Inspect operations
        registerInspectHandlers();
        // Random operations
        registerRandomHandlers();
        // Filesystem operations
        registerFilesystemHandlers();
        // Memory filesystem operations
        registerMemoryFilesystemHandlers();
        // Environment operations
        registerEnvironmentHandlers();
        // Async operations (Loom, OS threads, Coop)
        registerAsyncHandlers();
        // Concurrency primitives (TVar, Atom, Mutex, etc.)
        registerConcurrencyHandlers();
        // Buffer, JSON, HTTP Mock
        registerIOHandlers();
    }

    // ==================== ArrayList ====================

    private static void registerArrayListHandlers() {
        HANDLERS.put("__native_arraylist_length", (r, c) ->
            ArrayListNatives.length(getArray(r, "xs")));
        HANDLERS.put("__native_arraylist_is_empty", (r, c) ->
            ArrayListNatives.isEmpty(getArray(r, "xs")));
        HANDLERS.put("__native_arraylist_get", (r, c) ->
            ArrayListNatives.get(getArray(r, "xs"), getLong(r, "i")));
        HANDLERS.put("__native_arraylist_set", (r, c) ->
            ArrayListNatives.set(getArray(r, "xs"), getLong(r, "i"), getField(r, "x")));
        HANDLERS.put("__native_arraylist_add", (r, c) ->
            ArrayListNatives.add(getArray(r, "xs"), getField(r, "x")));
        HANDLERS.put("__native_arraylist_remove", (r, c) ->
            ArrayListNatives.remove(getArray(r, "xs"), getLong(r, "i")));
        HANDLERS.put("__native_arraylist_concat", (r, c) ->
            ArrayListNatives.concat(getArray(r, "xs"), getArray(r, "ys")));
        HANDLERS.put("__native_arraylist_reverse", (r, c) ->
            ArrayListNatives.reverse(getArray(r, "xs")));
        HANDLERS.put("__native_arraylist_slice", (r, c) ->
            ArrayListNatives.slice(getArray(r, "xs"), getLong(r, "start"), getLong(r, "end")));
        HANDLERS.put("__native_arraylist_contains", (r, c) ->
            ArrayListNatives.contains(getArray(r, "xs"), getField(r, "x")));
        HANDLERS.put("__native_arraylist_index_of", (r, c) ->
            ArrayListNatives.indexOf(getArray(r, "xs"), getField(r, "x")));
        HANDLERS.put("__native_arraylist_last", (r, c) ->
            ArrayListNatives.last(getArray(r, "xs")));
        HANDLERS.put("__native_arraylist_init", (r, c) ->
            ArrayListNatives.init(getArray(r, "xs")));
        HANDLERS.put("__native_arraylist_sort", (r, c) ->
            ArrayListNatives.sort(getArray(r, "xs")));
        HANDLERS.put("__native_arraylist_unique", (r, c) ->
            ArrayListNatives.unique(getArray(r, "xs")));
        HANDLERS.put("__native_arraylist_flatten", (r, c) ->
            ArrayListNatives.flatten(getArray(r, "xss")));
        HANDLERS.put("__native_arraylist_equals", (r, c) ->
            ArrayListNatives.equals(getArray(r, "l"), getArray(r, "r")));

        // HOF operations
        HANDLERS.put("__native_arraylist_map", (r, c) ->
            ArrayListNatives.map(getArray(r, "xs"), getFunction(r, "f"), c));
        HANDLERS.put("__native_arraylist_filter", (r, c) ->
            ArrayListNatives.filter(getArray(r, "xs"), getFunction(r, "p"), c));
        HANDLERS.put("__native_arraylist_fold", (r, c) ->
            ArrayListNatives.fold(getArray(r, "xs"), getField(r, "init"), getFunction(r, "f"), c));
        HANDLERS.put("__native_arraylist_reduce", (r, c) ->
            ArrayListNatives.reduce(getArray(r, "xs"), getFunction(r, "f"), c));
        HANDLERS.put("__native_arraylist_find", (r, c) ->
            ArrayListNatives.find(getArray(r, "xs"), getFunction(r, "p"), c));
        HANDLERS.put("__native_arraylist_find_index", (r, c) ->
            ArrayListNatives.findIndex(getArray(r, "xs"), getFunction(r, "p"), c));
        HANDLERS.put("__native_arraylist_any", (r, c) ->
            ArrayListNatives.any(getArray(r, "xs"), getFunction(r, "p"), c));
        HANDLERS.put("__native_arraylist_all", (r, c) ->
            ArrayListNatives.all(getArray(r, "xs"), getFunction(r, "p"), c));
        HANDLERS.put("__native_arraylist_partition", (r, c) ->
            ArrayListNatives.partition(getArray(r, "xs"), getFunction(r, "p"), c));
        HANDLERS.put("__native_arraylist_sort_by", (r, c) ->
            ArrayListNatives.sortBy(getArray(r, "xs"), getFunction(r, "cmp"), c));
        HANDLERS.put("__native_arraylist_zip", (r, c) ->
            ArrayListNatives.zip(getArray(r, "xs"), getArray(r, "ys")));
        HANDLERS.put("__native_arraylist_unzip", (r, c) ->
            ArrayListNatives.unzip(getArray(r, "xs")));
    }

    // ==================== List ====================

    private static void registerListHandlers() {
        HANDLERS.put("__native_list_empty", (r, c) -> ListNatives.empty());
        HANDLERS.put("__native_list_length", (r, c) ->
            ListNatives.length(getList(r, "xs")));
        HANDLERS.put("__native_list_is_empty", (r, c) ->
            ListNatives.isEmpty(getList(r, "xs")));
        HANDLERS.put("__native_list_head", (r, c) ->
            ListNatives.head(getList(r, "xs")));
        HANDLERS.put("__native_list_tail", (r, c) ->
            ListNatives.tail(getList(r, "xs")));
        HANDLERS.put("__native_list_last", (r, c) ->
            ListNatives.last(getList(r, "xs")));
        HANDLERS.put("__native_list_init", (r, c) ->
            ListNatives.init(getList(r, "xs")));
        HANDLERS.put("__native_list_cons", (r, c) ->
            ListNatives.cons(getField(r, "x"), getList(r, "xs")));
        HANDLERS.put("__native_list_append", (r, c) ->
            ListNatives.append(getList(r, "xs"), getField(r, "x")));
        HANDLERS.put("__native_list_uncons", (r, c) ->
            ListNatives.uncons(getList(r, "xs")));
        HANDLERS.put("__native_list_get", (r, c) ->
            ListNatives.get(getList(r, "xs"), getLong(r, "index")));
        HANDLERS.put("__native_list_concat", (r, c) ->
            ListNatives.concat(getList(r, "xs"), getList(r, "ys")));
        HANDLERS.put("__native_list_take", (r, c) ->
            ListNatives.take(getList(r, "xs"), getLong(r, "n")));
        HANDLERS.put("__native_list_drop", (r, c) ->
            ListNatives.drop(getList(r, "xs"), getLong(r, "n")));
        HANDLERS.put("__native_list_reverse", (r, c) ->
            ListNatives.reverse(getList(r, "xs")));
        HANDLERS.put("__native_list_contains", (r, c) ->
            ListNatives.contains(getList(r, "xs"), getField(r, "x")));
        HANDLERS.put("__native_list_sort", (r, c) ->
            ListNatives.sort(getList(r, "xs")));
        HANDLERS.put("__native_list_range", (r, c) ->
            ListNatives.range(getLong(r, "start"), getLong(r, "end")));
        HANDLERS.put("__native_list_repeat", (r, c) ->
            ListNatives.repeat(getField(r, "x"), getLong(r, "n")));
        HANDLERS.put("__native_list_intersperse", (r, c) ->
            ListNatives.intersperse(getList(r, "xs"), getField(r, "sep")));
        HANDLERS.put("__native_list_to_array", (r, c) ->
            ListNatives.toArray(getList(r, "xs")));
        HANDLERS.put("__native_list_from_array", (r, c) ->
            ListNatives.fromArray(getArray(r, "xs")));
        HANDLERS.put("__native_list_zip", (r, c) ->
            ListNatives.zip(getList(r, "xs"), getList(r, "ys")));
        HANDLERS.put("__native_list_unzip", (r, c) ->
            ListNatives.unzip(getList(r, "xs")));

        // HOF operations
        HANDLERS.put("__native_list_map", (r, c) ->
            ListNatives.map(getList(r, "xs"), getFunction(r, "f"), c));
        HANDLERS.put("__native_list_filter", (r, c) ->
            ListNatives.filter(getList(r, "xs"), getFunction(r, "f"), c));
        HANDLERS.put("__native_list_fold_left", (r, c) ->
            ListNatives.foldLeft(getList(r, "xs"), getField(r, "init"), getFunction(r, "f"), c));
        HANDLERS.put("__native_list_fold_right", (r, c) ->
            ListNatives.foldRight(getList(r, "xs"), getField(r, "init"), getFunction(r, "f"), c));
        HANDLERS.put("__native_list_reduce", (r, c) ->
            ListNatives.reduce(getList(r, "xs"), getFunction(r, "f"), c));
        HANDLERS.put("__native_list_find", (r, c) ->
            ListNatives.find(getList(r, "xs"), getFunction(r, "f"), c));
        HANDLERS.put("__native_list_find_index", (r, c) ->
            ListNatives.findIndex(getList(r, "xs"), getFunction(r, "f"), c));
        HANDLERS.put("__native_list_any", (r, c) ->
            ListNatives.any(getList(r, "xs"), getFunction(r, "f"), c));
        HANDLERS.put("__native_list_all", (r, c) ->
            ListNatives.all(getList(r, "xs"), getFunction(r, "f"), c));
        HANDLERS.put("__native_list_partition", (r, c) ->
            ListNatives.partition(getList(r, "xs"), getFunction(r, "f"), c));
        HANDLERS.put("__native_list_sort_by", (r, c) ->
            ListNatives.sortBy(getList(r, "xs"), getFunction(r, "f"), c));
    }

    // ==================== Set ====================

    private static void registerSetHandlers() {
        HANDLERS.put("__native_set_empty", (r, c) -> SetNatives.empty());
        HANDLERS.put("__native_set_size", (r, c) ->
            SetNatives.size(getSet(r, "s")));
        HANDLERS.put("__native_set_is_empty", (r, c) ->
            SetNatives.isEmpty(getSet(r, "s")));
        HANDLERS.put("__native_set_contains", (r, c) ->
            SetNatives.contains(getSet(r, "s"), getField(r, "x")));
        HANDLERS.put("__native_set_insert", (r, c) ->
            SetNatives.insert(getSet(r, "s"), getField(r, "x")));
        HANDLERS.put("__native_set_remove", (r, c) ->
            SetNatives.remove(getSet(r, "s"), getField(r, "x")));
        HANDLERS.put("__native_set_insert_all", (r, c) ->
            SetNatives.insertAll(getSet(r, "s"), getArray(r, "xs")));
        HANDLERS.put("__native_set_remove_all", (r, c) ->
            SetNatives.removeAll(getSet(r, "s"), getArray(r, "xs")));
        HANDLERS.put("__native_set_union", (r, c) ->
            SetNatives.union(getSet(r, "s1"), getSet(r, "s2")));
        HANDLERS.put("__native_set_intersection", (r, c) ->
            SetNatives.intersection(getSet(r, "s1"), getSet(r, "s2")));
        HANDLERS.put("__native_set_difference", (r, c) ->
            SetNatives.difference(getSet(r, "s1"), getSet(r, "s2")));
        HANDLERS.put("__native_set_is_subset", (r, c) ->
            SetNatives.isSubset(getSet(r, "s1"), getSet(r, "s2")));
        HANDLERS.put("__native_set_equals", (r, c) ->
            SetNatives.equals(getSet(r, "s1"), getSet(r, "s2")));
        HANDLERS.put("__native_set_to_array", (r, c) ->
            SetNatives.toArray(getSet(r, "s")));
        HANDLERS.put("__native_set_to_list", (r, c) ->
            SetNatives.toList(getSet(r, "s")));
        HANDLERS.put("__native_set_from_array", (r, c) ->
            SetNatives.fromArray(getArray(r, "xs")));
        HANDLERS.put("__native_set_from_list", (r, c) ->
            SetNatives.fromList(getList(r, "xs")));

        // HOF operations
        HANDLERS.put("__native_set_filter", (r, c) ->
            SetNatives.filter(getSet(r, "s"), getFunction(r, "p"), c));
        HANDLERS.put("__native_set_map", (r, c) ->
            SetNatives.map(getSet(r, "s"), getFunction(r, "f"), c));
        HANDLERS.put("__native_set_fold", (r, c) ->
            SetNatives.fold(getSet(r, "s"), getField(r, "init"), getFunction(r, "f"), c));
    }

    // ==================== SortedSet ====================

    private static void registerSortedSetHandlers() {
        HANDLERS.put("__native_sorted_set_empty", (r, c) -> SortedSetNatives.empty());
        HANDLERS.put("__native_sorted_set_singleton", (r, c) ->
            SortedSetNatives.singleton(getField(r, "x")));
        HANDLERS.put("__native_sorted_set_size", (r, c) ->
            SortedSetNatives.size(getSortedSet(r, "s")));
        HANDLERS.put("__native_sorted_set_is_empty", (r, c) ->
            SortedSetNatives.isEmpty(getSortedSet(r, "s")));
        HANDLERS.put("__native_sorted_set_contains", (r, c) ->
            SortedSetNatives.contains(getSortedSet(r, "s"), getField(r, "x")));
        HANDLERS.put("__native_sorted_set_add", (r, c) ->
            SortedSetNatives.add(getSortedSet(r, "s"), getField(r, "x")));
        HANDLERS.put("__native_sorted_set_remove", (r, c) ->
            SortedSetNatives.remove(getSortedSet(r, "s"), getField(r, "x")));
        HANDLERS.put("__native_sorted_set_min", (r, c) ->
            SortedSetNatives.min(getSortedSet(r, "s")));
        HANDLERS.put("__native_sorted_set_max", (r, c) ->
            SortedSetNatives.max(getSortedSet(r, "s")));
        HANDLERS.put("__native_sorted_set_union", (r, c) ->
            SortedSetNatives.union(getSortedSet(r, "s1"), getSortedSet(r, "s2")));
        HANDLERS.put("__native_sorted_set_intersect", (r, c) ->
            SortedSetNatives.intersect(getSortedSet(r, "s1"), getSortedSet(r, "s2")));
        HANDLERS.put("__native_sorted_set_difference", (r, c) ->
            SortedSetNatives.difference(getSortedSet(r, "s1"), getSortedSet(r, "s2")));
        HANDLERS.put("__native_sorted_set_equals", (r, c) ->
            SortedSetNatives.equals(getSortedSet(r, "s1"), getSortedSet(r, "s2")));
        HANDLERS.put("__native_sorted_set_to_list", (r, c) ->
            SortedSetNatives.toList(getSortedSet(r, "s")));
        HANDLERS.put("__native_sorted_set_to_set", (r, c) ->
            SortedSetNatives.toSet(getSortedSet(r, "s")));
        HANDLERS.put("__native_sorted_set_from_list", (r, c) ->
            SortedSetNatives.fromList(getList(r, "xs")));
        HANDLERS.put("__native_sorted_set_from_set", (r, c) ->
            SortedSetNatives.fromSet(getSet(r, "s")));
    }

    // ==================== Map ====================

    private static void registerMapHandlers() {
        HANDLERS.put("__native_map_empty", (r, c) -> MapNatives.empty());
        HANDLERS.put("__native_map_size", (r, c) ->
            MapNatives.size(getMap(r, "m")));
        HANDLERS.put("__native_map_is_empty", (r, c) ->
            MapNatives.isEmpty(getMap(r, "m")));
        HANDLERS.put("__native_map_contains_key", (r, c) ->
            MapNatives.containsKey(getMap(r, "m"), getField(r, "k")));
        HANDLERS.put("__native_map_get", (r, c) ->
            MapNatives.get(getMap(r, "m"), getField(r, "k")));
        HANDLERS.put("__native_map_insert", (r, c) ->
            MapNatives.insert(getMap(r, "m"), getField(r, "k"), getField(r, "v")));
        HANDLERS.put("__native_map_remove", (r, c) ->
            MapNatives.remove(getMap(r, "m"), getField(r, "k")));
        HANDLERS.put("__native_map_remove_all", (r, c) ->
            MapNatives.removeAll(getMap(r, "m"), getArray(r, "ks")));
        HANDLERS.put("__native_map_keys", (r, c) ->
            MapNatives.keys(getMap(r, "m")));
        HANDLERS.put("__native_map_values", (r, c) ->
            MapNatives.values(getMap(r, "m")));
        HANDLERS.put("__native_map_entries", (r, c) ->
            MapNatives.entries(getMap(r, "m")));
        HANDLERS.put("__native_map_merge", (r, c) ->
            MapNatives.merge(getMap(r, "m1"), getMap(r, "m2")));
        HANDLERS.put("__native_map_equals", (r, c) ->
            MapNatives.equals(getMap(r, "m1"), getMap(r, "m2")));

        // HOF operations
        HANDLERS.put("__native_map_filter", (r, c) ->
            MapNatives.filter(getMap(r, "m"), getFunction(r, "p"), c));
        HANDLERS.put("__native_map_map_values", (r, c) ->
            MapNatives.mapValues(getMap(r, "m"), getFunction(r, "f"), c));
        HANDLERS.put("__native_map_fold", (r, c) ->
            MapNatives.fold(getMap(r, "m"), getField(r, "init"), getFunction(r, "f"), c));
        HANDLERS.put("__native_map_merge_with", (r, c) ->
            MapNatives.mergeWith(getMap(r, "m1"), getMap(r, "m2"), getFunction(r, "f"), c));
        HANDLERS.put("__native_map_from_keys", (r, c) ->
            MapNatives.fromKeys(getArray(r, "ks"), getFunction(r, "f"), c));
    }

    // ==================== SortedMap ====================

    private static void registerSortedMapHandlers() {
        HANDLERS.put("__native_sorted_map_empty", (r, c) -> SortedMapNatives.empty());
        HANDLERS.put("__native_sorted_map_size", (r, c) ->
            SortedMapNatives.size(getSortedMap(r, "m")));
        HANDLERS.put("__native_sorted_map_is_empty", (r, c) ->
            SortedMapNatives.isEmpty(getSortedMap(r, "m")));
        HANDLERS.put("__native_sorted_map_contains_key", (r, c) ->
            SortedMapNatives.containsKey(getSortedMap(r, "m"), getField(r, "k")));
        HANDLERS.put("__native_sorted_map_get", (r, c) ->
            SortedMapNatives.get(getSortedMap(r, "m"), getField(r, "k")));
        HANDLERS.put("__native_sorted_map_insert", (r, c) ->
            SortedMapNatives.insert(getSortedMap(r, "m"), getField(r, "k"), getField(r, "v")));
        HANDLERS.put("__native_sorted_map_remove", (r, c) ->
            SortedMapNatives.remove(getSortedMap(r, "m"), getField(r, "k")));
        HANDLERS.put("__native_sorted_map_keys", (r, c) ->
            SortedMapNatives.keys(getSortedMap(r, "m")));
        HANDLERS.put("__native_sorted_map_values", (r, c) ->
            SortedMapNatives.values(getSortedMap(r, "m")));
        HANDLERS.put("__native_sorted_map_entries", (r, c) ->
            SortedMapNatives.entries(getSortedMap(r, "m")));
        HANDLERS.put("__native_sorted_map_equals", (r, c) ->
            SortedMapNatives.equals(getSortedMap(r, "a"), getSortedMap(r, "b")));
        HANDLERS.put("__native_sorted_map_min_entry", (r, c) ->
            SortedMapNatives.minEntry(getSortedMap(r, "m")));
        HANDLERS.put("__native_sorted_map_max_entry", (r, c) ->
            SortedMapNatives.maxEntry(getSortedMap(r, "m")));
        HANDLERS.put("__native_sorted_map_to_map", (r, c) ->
            SortedMapNatives.toMap(getSortedMap(r, "m")));
    }

    // ==================== Deque ====================

    private static void registerDequeHandlers() {
        HANDLERS.put("__native_deque_empty", (r, c) -> DequeNatives.empty());
        HANDLERS.put("__native_deque_singleton", (r, c) ->
            DequeNatives.singleton(getField(r, "x")));
        HANDLERS.put("__native_deque_length", (r, c) ->
            DequeNatives.length(getDeque(r, "d")));
        HANDLERS.put("__native_deque_is_empty", (r, c) ->
            DequeNatives.isEmpty(getDeque(r, "d")));
        HANDLERS.put("__native_deque_push_front", (r, c) ->
            DequeNatives.pushFront(getDeque(r, "d"), getField(r, "x")));
        HANDLERS.put("__native_deque_push_back", (r, c) ->
            DequeNatives.pushBack(getDeque(r, "d"), getField(r, "x")));
        HANDLERS.put("__native_deque_pop_front", (r, c) ->
            DequeNatives.popFront(getDeque(r, "d")));
        HANDLERS.put("__native_deque_pop_back", (r, c) ->
            DequeNatives.popBack(getDeque(r, "d")));
        HANDLERS.put("__native_deque_peek_front", (r, c) ->
            DequeNatives.peekFront(getDeque(r, "d")));
        HANDLERS.put("__native_deque_peek_back", (r, c) ->
            DequeNatives.peekBack(getDeque(r, "d")));
        HANDLERS.put("__native_deque_reverse", (r, c) ->
            DequeNatives.reverse(getDeque(r, "d")));
        HANDLERS.put("__native_deque_concat", (r, c) ->
            DequeNatives.concat(getDeque(r, "d1"), getDeque(r, "d2")));
        HANDLERS.put("__native_deque_equals", (r, c) ->
            DequeNatives.equals(getDeque(r, "d1"), getDeque(r, "d2")));
        HANDLERS.put("__native_deque_to_list", (r, c) ->
            DequeNatives.toList(getDeque(r, "d")));
        HANDLERS.put("__native_deque_to_array", (r, c) ->
            DequeNatives.toArray(getDeque(r, "d")));
        HANDLERS.put("__native_deque_from_list", (r, c) ->
            DequeNatives.fromList(getList(r, "xs")));

        // HOF operations
        HANDLERS.put("__native_deque_map", (r, c) ->
            DequeNatives.map(getDeque(r, "d"), getFunction(r, "f"), c));
        HANDLERS.put("__native_deque_filter", (r, c) ->
            DequeNatives.filter(getDeque(r, "d"), getFunction(r, "p"), c));
        HANDLERS.put("__native_deque_fold", (r, c) ->
            DequeNatives.fold(getDeque(r, "d"), getField(r, "init"), getFunction(r, "f"), c));
    }

    // ==================== NonEmptyList ====================

    private static void registerNonEmptyListHandlers() {
        HANDLERS.put("__native_nel_singleton", (r, c) ->
            NonEmptyListNatives.singleton(getField(r, "x")));
        HANDLERS.put("__native_nel_from_head_tail", (r, c) ->
            NonEmptyListNatives.fromHeadTail(getField(r, "head"), getList(r, "tail")));
        HANDLERS.put("__native_nel_from_list", (r, c) ->
            NonEmptyListNatives.fromList(getList(r, "xs")));
        HANDLERS.put("__native_nel_from_array", (r, c) ->
            NonEmptyListNatives.fromArray(getArray(r, "xs")));
        HANDLERS.put("__native_nel_length", (r, c) ->
            NonEmptyListNatives.length(getNonEmptyList(r, "xs")));
        HANDLERS.put("__native_nel_head", (r, c) ->
            NonEmptyListNatives.head(getNonEmptyList(r, "xs")));
        HANDLERS.put("__native_nel_tail", (r, c) ->
            NonEmptyListNatives.tail(getNonEmptyList(r, "xs")));
        HANDLERS.put("__native_nel_last", (r, c) ->
            NonEmptyListNatives.last(getNonEmptyList(r, "xs")));
        HANDLERS.put("__native_nel_get", (r, c) ->
            NonEmptyListNatives.get(getNonEmptyList(r, "xs"), getLong(r, "index")));
        HANDLERS.put("__native_nel_cons", (r, c) ->
            NonEmptyListNatives.cons(getField(r, "x"), getNonEmptyList(r, "xs")));
        HANDLERS.put("__native_nel_append", (r, c) ->
            NonEmptyListNatives.append(getNonEmptyList(r, "xs"), getNonEmptyList(r, "ys")));
        HANDLERS.put("__native_nel_append_list", (r, c) ->
            NonEmptyListNatives.appendList(getNonEmptyList(r, "xs"), getList(r, "ys")));
        HANDLERS.put("__native_nel_prepend_list", (r, c) ->
            NonEmptyListNatives.prependList(getList(r, "xs"), getNonEmptyList(r, "y")));
        HANDLERS.put("__native_nel_reverse", (r, c) ->
            NonEmptyListNatives.reverse(getNonEmptyList(r, "xs")));
        HANDLERS.put("__native_nel_contains", (r, c) ->
            NonEmptyListNatives.contains(getNonEmptyList(r, "xs"), getField(r, "x")));
        HANDLERS.put("__native_nel_intersperse", (r, c) ->
            NonEmptyListNatives.intersperse(getNonEmptyList(r, "xs"), getField(r, "sep")));
        HANDLERS.put("__native_nel_sort", (r, c) ->
            NonEmptyListNatives.sort(getNonEmptyList(r, "xs")));
        HANDLERS.put("__native_nel_to_list", (r, c) ->
            NonEmptyListNatives.toList(getNonEmptyList(r, "xs")));
        HANDLERS.put("__native_nel_to_array", (r, c) ->
            NonEmptyListNatives.toArray(getNonEmptyList(r, "xs")));
        HANDLERS.put("__native_nel_zip", (r, c) ->
            NonEmptyListNatives.zip(getNonEmptyList(r, "xs"), getNonEmptyList(r, "ys")));

        // HOF operations
        HANDLERS.put("__native_nel_map", (r, c) ->
            NonEmptyListNatives.map(getNonEmptyList(r, "xs"), getFunction(r, "f"), c));
        HANDLERS.put("__native_nel_flat_map", (r, c) ->
            NonEmptyListNatives.flatMap(getNonEmptyList(r, "xs"), getFunction(r, "f"), c));
        HANDLERS.put("__native_nel_fold_left", (r, c) ->
            NonEmptyListNatives.foldLeft(getNonEmptyList(r, "xs"), getField(r, "init"), getFunction(r, "f"), c));
        HANDLERS.put("__native_nel_fold_right", (r, c) ->
            NonEmptyListNatives.foldRight(getNonEmptyList(r, "xs"), getField(r, "init"), getFunction(r, "f"), c));
        HANDLERS.put("__native_nel_reduce", (r, c) ->
            NonEmptyListNatives.reduce(getNonEmptyList(r, "xs"), getFunction(r, "f"), c));
        HANDLERS.put("__native_nel_any", (r, c) ->
            NonEmptyListNatives.any(getNonEmptyList(r, "xs"), getFunction(r, "p"), c));
        HANDLERS.put("__native_nel_all", (r, c) ->
            NonEmptyListNatives.all(getNonEmptyList(r, "xs"), getFunction(r, "p"), c));
        HANDLERS.put("__native_nel_sort_by", (r, c) ->
            NonEmptyListNatives.sortBy(getNonEmptyList(r, "nel"), getFunction(r, "f"), c));
    }

    // ==================== Nat ====================

    private static void registerNatHandlers() {
        HANDLERS.put("__native_nat_from_int", (r, c) ->
            NatNatives.fromInt(getLong(r, "n")));
        HANDLERS.put("__native_nat_from_string", (r, c) ->
            NatNatives.fromString(getString(r, "s")));
        HANDLERS.put("__native_nat_from_float", (r, c) ->
            NatNatives.fromFloat(getDouble(r, "n")));
        HANDLERS.put("__native_nat_to_int", (r, c) ->
            NatNatives.toInt(getLong(r, "n")));
        HANDLERS.put("__native_nat_to_string", (r, c) ->
            NatNatives.toString(getLong(r, "n")));
        HANDLERS.put("__native_nat_subtract_to_int", (r, c) ->
            NatNatives.subtractToInt(getLong(r, "l"), getLong(r, "r")));
        HANDLERS.put("__native_nat_pow", (r, c) ->
            NatNatives.pow(getLong(r, "base"), getLong(r, "exp")));
        HANDLERS.put("__native_nat_min", (r, c) ->
            NatNatives.min(getLong(r, "l"), getLong(r, "r")));
        HANDLERS.put("__native_nat_max", (r, c) ->
            NatNatives.max(getLong(r, "l"), getLong(r, "r")));
        HANDLERS.put("__native_nat_clamp", (r, c) ->
            NatNatives.clamp(getLong(r, "n"), getLong(r, "min"), getLong(r, "max")));
    }

    // ==================== Char ====================

    private static void registerCharHandlers() {
        HANDLERS.put("__native_char_from_string", (r, c) ->
            CharNatives.fromString(getString(r, "s")));
        HANDLERS.put("__native_char_from_int", (r, c) ->
            CharNatives.fromInt(getLong(r, "code")));
        HANDLERS.put("__native_char_to_int", (r, c) ->
            CharNatives.toInt(getString(r, "c")));
        HANDLERS.put("__native_char_to_string", (r, c) ->
            CharNatives.toString(getString(r, "c")));
        HANDLERS.put("__native_char_is_letter", (r, c) ->
            CharNatives.isLetter(getString(r, "c")));
        HANDLERS.put("__native_char_is_digit", (r, c) ->
            CharNatives.isDigit(getString(r, "c")));
        HANDLERS.put("__native_char_is_alpha_numeric", (r, c) ->
            CharNatives.isAlphaNumeric(getString(r, "c")));
        HANDLERS.put("__native_char_is_whitespace", (r, c) ->
            CharNatives.isWhitespace(getString(r, "c")));
        HANDLERS.put("__native_char_is_upper", (r, c) ->
            CharNatives.isUpper(getString(r, "c")));
        HANDLERS.put("__native_char_is_lower", (r, c) ->
            CharNatives.isLower(getString(r, "c")));
        HANDLERS.put("__native_char_to_upper", (r, c) ->
            CharNatives.toUpper(getString(r, "c")));
        HANDLERS.put("__native_char_to_lower", (r, c) ->
            CharNatives.toLower(getString(r, "c")));
        HANDLERS.put("__native_char_digit_to_int", (r, c) ->
            CharNatives.digitToInt(getString(r, "c")));
        HANDLERS.put("__native_char_eq", (r, c) ->
            CharNatives.eq(getString(r, "a"), getString(r, "b")));
        HANDLERS.put("__native_char_compare", (r, c) ->
            CharNatives.compare(getString(r, "a"), getString(r, "b")));
    }

    // ==================== Frac ====================

    private static void registerFracHandlers() {
        HANDLERS.put("__native_make_frac", (r, c) ->
            FracNatives.make(getLong(r, "numerator"), getLong(r, "denominator")));
        HANDLERS.put("__native_int_to_frac", (r, c) ->
            FracNatives.fromInt(getLong(r, "n")));
        HANDLERS.put("__native_nat_to_frac", (r, c) ->
            FracNatives.fromNat(getLong(r, "n")));
        HANDLERS.put("__native_frac_numerator", (r, c) ->
            FracNatives.numerator(getRecord(r, "n")));
        HANDLERS.put("__native_frac_denominator", (r, c) ->
            FracNatives.denominator(getRecord(r, "n")));
        HANDLERS.put("__native_frac_add", (r, c) ->
            FracNatives.add(getRecord(r, "l"), getRecord(r, "r")));
        HANDLERS.put("__native_frac_subtract", (r, c) ->
            FracNatives.subtract(getRecord(r, "l"), getRecord(r, "r")));
        HANDLERS.put("__native_frac_multiply", (r, c) ->
            FracNatives.multiply(getRecord(r, "l"), getRecord(r, "r")));
        HANDLERS.put("__native_frac_divide", (r, c) ->
            FracNatives.divide(getRecord(r, "l"), getRecord(r, "r")));
        HANDLERS.put("__native_negate_frac", (r, c) ->
            FracNatives.negate(getRecord(r, "n")));
        HANDLERS.put("__native_abs_frac", (r, c) ->
            FracNatives.abs(getRecord(r, "n")));
        HANDLERS.put("__native_reduce_frac", (r, c) ->
            FracNatives.reduce(getRecord(r, "n")));
        HANDLERS.put("__native_frac_equals", (r, c) ->
            FracNatives.equals(getRecord(r, "l"), getRecord(r, "r")));
        HANDLERS.put("__native_frac_less_than", (r, c) ->
            FracNatives.lessThan(getRecord(r, "l"), getRecord(r, "r")));
        HANDLERS.put("__native_frac_greater_than", (r, c) ->
            FracNatives.greaterThan(getRecord(r, "l"), getRecord(r, "r")));
        HANDLERS.put("__native_frac_compare", (r, c) ->
            FracNatives.compare(getRecord(r, "l"), getRecord(r, "r")));
        HANDLERS.put("__native_frac_to_float", (r, c) ->
            FracNatives.toFloat(getRecord(r, "n")));
        HANDLERS.put("__native_frac_to_string", (r, c) ->
            FracNatives.fracToString(getRecord(r, "n")));
    }

    // ==================== Bytes ====================

    private static void registerBytesHandlers() {
        HANDLERS.put("__native_bytes_empty", (r, c) -> BytesNatives.empty());
        HANDLERS.put("__native_bytes_from_string", (r, c) ->
            BytesNatives.fromString(getString(r, "s")));
        HANDLERS.put("__native_bytes_from_array", (r, c) ->
            BytesNatives.fromArray(getArray(r, "arr")));
        HANDLERS.put("__native_bytes_from_hex", (r, c) ->
            BytesNatives.fromHex(getString(r, "s")));
        HANDLERS.put("__native_bytes_from_base64", (r, c) ->
            BytesNatives.fromBase64(getString(r, "s")));
        HANDLERS.put("__native_bytes_length", (r, c) ->
            BytesNatives.length(getBytes(r, "b")));
        HANDLERS.put("__native_bytes_is_empty", (r, c) ->
            BytesNatives.isEmpty(getBytes(r, "b")));
        HANDLERS.put("__native_bytes_get", (r, c) ->
            BytesNatives.get(getBytes(r, "b"), getLong(r, "i")));
        HANDLERS.put("__native_bytes_slice", (r, c) ->
            BytesNatives.slice(getBytes(r, "b"), getLong(r, "start"), getLong(r, "end")));
        HANDLERS.put("__native_bytes_take", (r, c) ->
            BytesNatives.take(getBytes(r, "b"), getLong(r, "n")));
        HANDLERS.put("__native_bytes_drop", (r, c) ->
            BytesNatives.drop(getBytes(r, "b"), getLong(r, "n")));
        HANDLERS.put("__native_bytes_concat", (r, c) ->
            BytesNatives.concat(getBytes(r, "b1"), getBytes(r, "b2")));
        HANDLERS.put("__native_bytes_concat_all", (r, c) ->
            BytesNatives.concatAll(getArray(r, "bs")));
        HANDLERS.put("__native_bytes_contains", (r, c) ->
            BytesNatives.contains(getBytes(r, "b"), getBytes(r, "pattern")));
        HANDLERS.put("__native_bytes_index_of", (r, c) ->
            BytesNatives.indexOf(getBytes(r, "b"), getBytes(r, "pattern")));
        HANDLERS.put("__native_bytes_starts_with", (r, c) ->
            BytesNatives.startsWith(getBytes(r, "b"), getBytes(r, "prefix")));
        HANDLERS.put("__native_bytes_ends_with", (r, c) ->
            BytesNatives.endsWith(getBytes(r, "b"), getBytes(r, "suffix")));
        HANDLERS.put("__native_bytes_reverse", (r, c) ->
            BytesNatives.reverse(getBytes(r, "b")));
        HANDLERS.put("__native_bytes_repeat", (r, c) ->
            BytesNatives.repeat(getBytes(r, "b"), getLong(r, "n")));
        HANDLERS.put("__native_bytes_to_string", (r, c) ->
            BytesNatives.bytesToString(getBytes(r, "b")));
        HANDLERS.put("__native_bytes_to_hex", (r, c) ->
            BytesNatives.toHex(getBytes(r, "b")));
        HANDLERS.put("__native_bytes_to_base64", (r, c) ->
            BytesNatives.toBase64(getBytes(r, "b")));
        HANDLERS.put("__native_bytes_to_array", (r, c) ->
            BytesNatives.toArray(getBytes(r, "b")));

        // HOF operations
        HANDLERS.put("__native_bytes_map", (r, c) ->
            BytesNatives.map(getBytes(r, "b"), getFunction(r, "f"), c));
        HANDLERS.put("__native_bytes_fold", (r, c) ->
            BytesNatives.fold(getBytes(r, "b"), getField(r, "init"), getFunction(r, "f"), c));
    }

    // ==================== Time ====================

    private static void registerTimeHandlers() {
        HANDLERS.put("__native_time_now", (r, c) -> TimeNatives.now());
        HANDLERS.put("__native_time_now_nanos", (r, c) -> TimeNatives.nowNanos());
        HANDLERS.put("__native_time_current_year", (r, c) -> TimeNatives.currentYear());
        HANDLERS.put("__native_time_current_month", (r, c) -> TimeNatives.currentMonth());
        HANDLERS.put("__native_time_current_day", (r, c) -> TimeNatives.currentDay());
        HANDLERS.put("__native_time_current_hour", (r, c) -> TimeNatives.currentHour());
        HANDLERS.put("__native_time_current_minute", (r, c) -> TimeNatives.currentMinute());
        HANDLERS.put("__native_time_current_second", (r, c) -> TimeNatives.currentSecond());
        HANDLERS.put("__native_time_year_from_timestamp", (r, c) ->
            TimeNatives.yearFromTimestamp(getLong(r, "timestamp")));
        HANDLERS.put("__native_time_month_from_timestamp", (r, c) ->
            TimeNatives.monthFromTimestamp(getLong(r, "timestamp")));
        HANDLERS.put("__native_time_day_from_timestamp", (r, c) ->
            TimeNatives.dayFromTimestamp(getLong(r, "timestamp")));
        HANDLERS.put("__native_time_hour_from_timestamp", (r, c) ->
            TimeNatives.hourFromTimestamp(getLong(r, "timestamp")));
        HANDLERS.put("__native_time_minute_from_timestamp", (r, c) ->
            TimeNatives.minuteFromTimestamp(getLong(r, "timestamp")));
        HANDLERS.put("__native_time_second_from_timestamp", (r, c) ->
            TimeNatives.secondFromTimestamp(getLong(r, "timestamp")));
        HANDLERS.put("__native_time_format", (r, c) ->
            TimeNatives.format(getLong(r, "timestamp"), getString(r, "format")));
        HANDLERS.put("__native_time_parse", (r, c) ->
            TimeNatives.parse(getString(r, "date_string"), getString(r, "format")));
        HANDLERS.put("__native_time_sleep", (r, c) ->
            TimeNatives.sleep(getLong(r, "millis")));
        HANDLERS.put("__native_time_sleep_nanos", (r, c) ->
            TimeNatives.sleepNanos(getLong(r, "nanos")));
    }

    // ==================== String Case Conversions ====================

    private static void registerStringCaseHandlers() {
        HANDLERS.put("__native_string_to_camel_case", (r, c) ->
            toCamelCase(getString(r, "s")));
        HANDLERS.put("__native_string_to_snake_case", (r, c) ->
            toSnakeCase(getString(r, "s")));
        HANDLERS.put("__native_string_to_kebab_case", (r, c) ->
            toKebabCase(getString(r, "s")));
        HANDLERS.put("__native_string_to_screaming_snake_case", (r, c) ->
            toScreamingSnakeCase(getString(r, "s")));
        HANDLERS.put("__native_string_lines", (r, c) ->
            stringLines(getString(r, "s")));
        HANDLERS.put("__native_string_words", (r, c) ->
            stringWords(getString(r, "s")));
        HANDLERS.put("__native_string_chars", (r, c) ->
            stringChars(getString(r, "s")));
        HANDLERS.put("__native_string_pad_left", (r, c) ->
            padLeft(getString(r, "s"), getLong(r, "len"), getString(r, "c")));
        HANDLERS.put("__native_string_pad_right", (r, c) ->
            padRight(getString(r, "s"), getLong(r, "len"), getString(r, "c")));
        HANDLERS.put("__native_string_last_index_of", (r, c) ->
            lastIndexOf(getString(r, "s"), getString(r, "sub")));
        HANDLERS.put("__native_string_glob_match", (r, c) ->
            globMatch(getString(r, "pattern"), getString(r, "s")));
    }

    // ==================== Float Additional ====================

    private static void registerFloatHandlers() {
        HANDLERS.put("__native_float_from_nat", (r, c) ->
            (double) getLong(r, "n"));
        HANDLERS.put("__native_float_from_string", (r, c) ->
            floatFromString(getString(r, "s")));
        HANDLERS.put("__native_float_clamp", (r, c) ->
            Math.max(getDouble(r, "min"), Math.min(getDouble(r, "max"), getDouble(r, "n"))));
        HANDLERS.put("__native_float_sign", (r, c) ->
            Math.signum(getDouble(r, "n")));
        HANDLERS.put("__native_float_to_int", (r, c) ->
            (long) getDouble(r, "n"));
        HANDLERS.put("__native_float_is_nan", (r, c) ->
            Double.isNaN(getDouble(r, "n")));
        HANDLERS.put("__native_float_is_infinite", (r, c) ->
            Double.isInfinite(getDouble(r, "n")));
        HANDLERS.put("__native_float_is_finite", (r, c) ->
            Double.isFinite(getDouble(r, "n")));
        HANDLERS.put("__native_float_ln", (r, c) ->
            Math.log(getDouble(r, "n")));
        HANDLERS.put("__native_float_log10", (r, c) ->
            Math.log10(getDouble(r, "n")));
        HANDLERS.put("__native_float_exp", (r, c) ->
            Math.exp(getDouble(r, "n")));
        HANDLERS.put("__native_float_asin", (r, c) ->
            Math.asin(getDouble(r, "n")));
        HANDLERS.put("__native_float_acos", (r, c) ->
            Math.acos(getDouble(r, "n")));
        HANDLERS.put("__native_float_atan", (r, c) ->
            Math.atan(getDouble(r, "n")));
        HANDLERS.put("__native_float_atan2", (r, c) ->
            Math.atan2(getDouble(r, "y"), getDouble(r, "x")));
    }

    // ==================== Field Accessors ====================

    private static Object getField(MezzeRecord r, String name) {
        int index = r.getFieldIndex(name);
        if (index < 0) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("Missing field '" + name + "'");
        }
        return r.getField(index);
    }

    private static long getLong(MezzeRecord r, String name) {
        Object val = getField(r, name);
        if (val instanceof Long l) return l;
        throw new RuntimeException("Expected Long for field '" + name + "'");
    }

    private static double getDouble(MezzeRecord r, String name) {
        Object val = getField(r, name);
        if (val instanceof Double d) return d;
        if (val instanceof Long l) return l.doubleValue();
        throw new RuntimeException("Expected Double for field '" + name + "'");
    }

    private static String getString(MezzeRecord r, String name) {
        Object val = getField(r, name);
        if (val instanceof String s) return s;
        throw new RuntimeException("Expected String for field '" + name + "'");
    }

    private static Object[] getArray(MezzeRecord r, String name) {
        Object val = getField(r, name);
        if (val instanceof Object[] arr) return arr;
        // Also handle MezzeList (which wraps various storage strategies)
        if (val instanceof MezzeList list) return list.getObjectStore();
        throw new RuntimeException("Expected Object[] for field '" + name + "', got: " +
            (val == null ? "null" : val.getClass().getSimpleName()));
    }

    private static MezzeList getList(MezzeRecord r, String name) {
        Object val = getField(r, name);
        if (val instanceof MezzeList list) return list;
        // Handle array literals (Object[]) - convert to MezzeList
        if (val instanceof Object[] arr) {
            return MezzeList.fromObjects(arr);
        }
        throw new RuntimeException("Expected MezzeList for field '" + name + "'");
    }

    private static MezzeFunction getFunction(MezzeRecord r, String name) {
        Object val = getField(r, name);
        if (val instanceof MezzeFunction fn) return fn;
        throw new RuntimeException("Expected MezzeFunction for field '" + name + "'");
    }

    private static MezzeRecord getRecord(MezzeRecord r, String name) {
        Object val = getField(r, name);
        if (val instanceof MezzeRecord rec) return rec;
        throw new RuntimeException("Expected MezzeRecord for field '" + name + "'");
    }

    private static SetNatives.MezzeSet getSet(MezzeRecord r, String name) {
        Object val = getField(r, name);
        if (val instanceof SetNatives.MezzeSet set) return set;
        throw new RuntimeException("Expected MezzeSet for field '" + name + "'");
    }

    private static SortedSetNatives.MezzeSortedSet getSortedSet(MezzeRecord r, String name) {
        Object val = getField(r, name);
        if (val instanceof SortedSetNatives.MezzeSortedSet set) return set;
        throw new RuntimeException("Expected MezzeSortedSet for field '" + name + "'");
    }

    private static MapNatives.MezzeMap getMap(MezzeRecord r, String name) {
        Object val = getField(r, name);
        if (val instanceof MapNatives.MezzeMap map) return map;
        throw new RuntimeException("Expected MezzeMap for field '" + name + "'");
    }

    private static SortedMapNatives.MezzeSortedMap getSortedMap(MezzeRecord r, String name) {
        Object val = getField(r, name);
        if (val instanceof SortedMapNatives.MezzeSortedMap map) return map;
        throw new RuntimeException("Expected MezzeSortedMap for field '" + name + "'");
    }

    private static DequeNatives.MezzeDeque getDeque(MezzeRecord r, String name) {
        Object val = getField(r, name);
        if (val instanceof DequeNatives.MezzeDeque deque) return deque;
        throw new RuntimeException("Expected MezzeDeque for field '" + name + "'");
    }

    private static NonEmptyListNatives.MezzeNonEmptyList getNonEmptyList(MezzeRecord r, String name) {
        Object val = getField(r, name);
        if (val instanceof NonEmptyListNatives.MezzeNonEmptyList nel) return nel;
        throw new RuntimeException("Expected MezzeNonEmptyList for field '" + name + "'");
    }

    private static BytesNatives.MezzeBytes getBytes(MezzeRecord r, String name) {
        Object val = getField(r, name);
        if (val instanceof BytesNatives.MezzeBytes bytes) return bytes;
        throw new RuntimeException("Expected MezzeBytes for field '" + name + "'");
    }

    // ==================== String Helper Methods ====================

    @TruffleBoundary
    private static String toCamelCase(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = false;
        for (char c : s.toCharArray()) {
            if (c == '_' || c == '-' || c == ' ') {
                capitalizeNext = true;
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                result.append(Character.toLowerCase(c));
            }
        }
        return result.toString();
    }

    @TruffleBoundary
    private static String toSnakeCase(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) result.append('_');
                result.append(Character.toLowerCase(c));
            } else if (c == '-' || c == ' ') {
                result.append('_');
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    @TruffleBoundary
    private static String toKebabCase(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) result.append('-');
                result.append(Character.toLowerCase(c));
            } else if (c == '_' || c == ' ') {
                result.append('-');
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    @TruffleBoundary
    private static String toScreamingSnakeCase(String s) {
        return toSnakeCase(s).toUpperCase();
    }

    @TruffleBoundary
    private static MezzeList stringLines(String s) {
        String[] lines = s.split("\n", -1);
        Object[] result = new Object[lines.length];
        System.arraycopy(lines, 0, result, 0, lines.length);
        return MezzeList.fromObjects(result);
    }

    @TruffleBoundary
    private static MezzeList stringWords(String s) {
        String[] words = s.trim().split("\\s+");
        if (words.length == 1 && words[0].isEmpty()) {
            return MezzeList.empty();
        }
        Object[] result = new Object[words.length];
        System.arraycopy(words, 0, result, 0, words.length);
        return MezzeList.fromObjects(result);
    }

    @TruffleBoundary
    private static MezzeList stringChars(String s) {
        Object[] result = new Object[s.length()];
        for (int i = 0; i < s.length(); i++) {
            result[i] = String.valueOf(s.charAt(i));
        }
        return MezzeList.fromObjects(result);
    }

    @TruffleBoundary
    private static String padLeft(String s, long width, String pad) {
        if (s.length() >= width) return s;
        String padding = pad.isEmpty() ? " " : pad;
        StringBuilder sb = new StringBuilder();
        while (sb.length() + s.length() < width) {
            sb.append(padding);
        }
        return sb.substring(0, Math.max(0, (int) width - s.length())) + s;
    }

    @TruffleBoundary
    private static String padRight(String s, long width, String pad) {
        if (s.length() >= width) return s;
        String padding = pad.isEmpty() ? " " : pad;
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) {
            sb.append(padding);
        }
        return sb.substring(0, (int) width);
    }

    @TruffleBoundary
    private static MezzeVariant lastIndexOf(String s, String sub) {
        int idx = s.lastIndexOf(sub);
        if (idx < 0) return MezzeVariant.none();
        return MezzeVariant.some((long) idx);
    }

    @TruffleBoundary
    private static boolean globMatch(String pattern, String s) {
        // Simple glob matching (* = any, ? = single char)
        String regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".");
        return s.matches(regex);
    }

    @TruffleBoundary
    private static MezzeVariant floatFromString(String s) {
        try {
            return MezzeVariant.some(Double.parseDouble(s.trim()));
        } catch (NumberFormatException e) {
            return MezzeVariant.none();
        }
    }

    // ==================== Log ====================

    private static void registerLogHandlers() {
        HANDLERS.put("__native_console_print", (r, c) -> LogNatives.consolePrint(r));
        HANDLERS.put("__native_console_println", (r, c) -> LogNatives.consolePrintln(r));
        HANDLERS.put("__native_log_info", (r, c) -> LogNatives.logInfo(r));
        HANDLERS.put("__native_log_warn", (r, c) -> LogNatives.logWarn(r));
        HANDLERS.put("__native_log_error", (r, c) -> LogNatives.logError(r));
        HANDLERS.put("__native_log_debug", (r, c) -> LogNatives.logDebug(r));
        HANDLERS.put("__native_log_trace", (r, c) -> LogNatives.logTrace(r));
        // Legacy aliases without double underscore prefix (used by some tests)
        HANDLERS.put("native_print", (r, c) -> LogNatives.consolePrintln(r));
    }

    // ==================== Inspect ====================

    private static void registerInspectHandlers() {
        HANDLERS.put("__native_inspect_to_str", (r, c) -> InspectNatives.toStr(r));
    }

    // ==================== Random ====================

    private static void registerRandomHandlers() {
        HANDLERS.put("__native_random_float", (r, c) -> RandomNatives.randomFloat(r));
        HANDLERS.put("__native_random_int", (r, c) -> RandomNatives.randomInt(r));
        HANDLERS.put("__native_random_nat", (r, c) -> RandomNatives.randomNat(r));
        HANDLERS.put("__native_random_seed", (r, c) -> RandomNatives.randomSeed(r));
        HANDLERS.put("__native_seeded_random_float", (r, c) -> RandomNatives.seededRandomFloat(r));
        HANDLERS.put("__native_seeded_random_int", (r, c) -> RandomNatives.seededRandomInt(r));
        HANDLERS.put("__native_seeded_random_nat", (r, c) -> RandomNatives.seededRandomNat(r));
        HANDLERS.put("__native_update_seed", (r, c) -> RandomNatives.updateSeed(r));
        // Legacy aliases without double underscore prefix (used by some tests)
        HANDLERS.put("native_random_int", (r, c) -> RandomNatives.randomInt(r));
        HANDLERS.put("native_random_double", (r, c) -> RandomNatives.randomFloat(r));
    }

    // ==================== Filesystem ====================

    private static void registerFilesystemHandlers() {
        HANDLERS.put("__native_fs_read_file", (r, c) -> FilesystemNatives.readFile(r));
        HANDLERS.put("__native_fs_write_file", (r, c) -> FilesystemNatives.writeFile(r));
        HANDLERS.put("__native_fs_append_file", (r, c) -> FilesystemNatives.appendFile(r));
        HANDLERS.put("__native_fs_exists", (r, c) -> FilesystemNatives.exists(r));
        HANDLERS.put("__native_fs_delete", (r, c) -> FilesystemNatives.delete(r));
        HANDLERS.put("__native_fs_delete_recursive", (r, c) -> FilesystemNatives.deleteRecursive(r));
        HANDLERS.put("__native_fs_create_directory", (r, c) -> FilesystemNatives.createDirectory(r));
        HANDLERS.put("__native_fs_create_directories", (r, c) -> FilesystemNatives.createDirectories(r));
        HANDLERS.put("__native_fs_list_directory", (r, c) -> FilesystemNatives.listDirectory(r));
        HANDLERS.put("__native_fs_file_size", (r, c) -> FilesystemNatives.fileSize(r));
        HANDLERS.put("__native_fs_is_directory", (r, c) -> FilesystemNatives.isDirectory(r));
        HANDLERS.put("__native_fs_is_file", (r, c) -> FilesystemNatives.isFile(r));
        HANDLERS.put("__native_fs_copy_file", (r, c) -> FilesystemNatives.copyFile(r));
        HANDLERS.put("__native_fs_move_file", (r, c) -> FilesystemNatives.moveFile(r));
    }

    // ==================== Memory Filesystem ====================

    private static void registerMemoryFilesystemHandlers() {
        HANDLERS.put("__native_memfs_read_file", (r, c) -> MemoryFilesystemNatives.readFile(r));
        HANDLERS.put("__native_memfs_write_file", (r, c) -> MemoryFilesystemNatives.writeFile(r));
        HANDLERS.put("__native_memfs_append_file", (r, c) -> MemoryFilesystemNatives.appendFile(r));
        HANDLERS.put("__native_memfs_exists", (r, c) -> MemoryFilesystemNatives.exists(r));
        HANDLERS.put("__native_memfs_delete", (r, c) -> MemoryFilesystemNatives.delete(r));
        HANDLERS.put("__native_memfs_delete_recursive", (r, c) -> MemoryFilesystemNatives.deleteRecursive(r));
        HANDLERS.put("__native_memfs_create_directory", (r, c) -> MemoryFilesystemNatives.createDirectory(r));
        HANDLERS.put("__native_memfs_create_directories", (r, c) -> MemoryFilesystemNatives.createDirectories(r));
        HANDLERS.put("__native_memfs_list_directory", (r, c) -> MemoryFilesystemNatives.listDirectory(r));
        HANDLERS.put("__native_memfs_file_size", (r, c) -> MemoryFilesystemNatives.fileSize(r));
        HANDLERS.put("__native_memfs_is_directory", (r, c) -> MemoryFilesystemNatives.isDirectoryOp(r));
        HANDLERS.put("__native_memfs_is_file", (r, c) -> MemoryFilesystemNatives.isFileOp(r));
        HANDLERS.put("__native_memfs_copy_file", (r, c) -> MemoryFilesystemNatives.copyFile(r));
        HANDLERS.put("__native_memfs_move_file", (r, c) -> MemoryFilesystemNatives.moveFile(r));
        // Clear operation for test isolation
        HANDLERS.put("__native_memfs_clear", (r, c) -> {
            MemoryFilesystemNatives.clear();
            return MezzeUnit.INSTANCE;
        });
    }

    // ==================== Environment ====================

    private static void registerEnvironmentHandlers() {
        HANDLERS.put("__native_env_get", (r, c) -> EnvironmentNatives.envGet(r));
        HANDLERS.put("__native_env_set", (r, c) -> EnvironmentNatives.envSet(r));
        HANDLERS.put("__native_env_get_all", (r, c) -> EnvironmentNatives.envGetAll(r));
        HANDLERS.put("__native_env_current_dir", (r, c) -> EnvironmentNatives.currentDir(r));
        HANDLERS.put("__native_env_change_dir", (r, c) -> EnvironmentNatives.changeDir(r));
        HANDLERS.put("__native_env_os_name", (r, c) -> EnvironmentNatives.osName(r));
        HANDLERS.put("__native_env_os_version", (r, c) -> EnvironmentNatives.osVersion(r));
        HANDLERS.put("__native_env_arch", (r, c) -> EnvironmentNatives.arch(r));
        HANDLERS.put("__native_env_process_id", (r, c) -> EnvironmentNatives.processId(r));
        HANDLERS.put("__native_env_process_args", (r, c) -> EnvironmentNatives.processArgs(r));
        HANDLERS.put("__native_env_exit", (r, c) -> EnvironmentNatives.exit(r));
    }

    // ==================== Async (Loom, OS Thread, Coop) ====================

    private static void registerAsyncHandlers() {
        // === Loom (Virtual Threads) - scales to millions ===
        // Operations that need TruffleContext for cross-thread execution use HANDLERS_WITH_NODE
        HANDLERS_WITH_NODE.put("__native_loom_spawn", (r, c, n) -> AsyncNatives.loomSpawn(r, c, n));
        HANDLERS_WITH_NODE.put("__native_loom_spawn_cfg", (r, c, n) -> AsyncNatives.loomSpawnCfg(r, c, n));
        HANDLERS_WITH_NODE.put("__native_loom_spawn_all", (r, c, n) -> AsyncNatives.loomSpawnAll(r, c, n));
        HANDLERS_WITH_NODE.put("__native_loom_join", (r, c, n) -> AsyncNatives.loomJoin(r, c, n));
        HANDLERS_WITH_NODE.put("__native_loom_join3", (r, c, n) -> AsyncNatives.loomJoin3(r, c, n));
        HANDLERS_WITH_NODE.put("__native_loom_map", (r, c, n) -> AsyncNatives.loomMap(r, c, n));
        HANDLERS_WITH_NODE.put("__native_loom_for_each", (r, c, n) -> AsyncNatives.loomForEach(r, c, n));
        HANDLERS_WITH_NODE.put("__native_loom_filter", (r, c, n) -> AsyncNatives.loomFilter(r, c, n));
        HANDLERS_WITH_NODE.put("__native_loom_scoped", (r, c, n) -> AsyncNatives.loomScoped(r, c, n));
        HANDLERS_WITH_NODE.put("__native_loom_scope_spawn", (r, c, n) -> AsyncNatives.loomScopeSpawn(r, c, n));

        // Simple operations that don't spawn threads use regular HANDLERS
        HANDLERS.put("__native_loom_await", (r, c) -> AsyncNatives.loomAwait(r));
        HANDLERS.put("__native_loom_race", (r, c) -> AsyncNatives.loomRace(r));
        HANDLERS.put("__native_loom_all", (r, c) -> AsyncNatives.loomAll(r));
        HANDLERS.put("__native_loom_timeout", (r, c) -> AsyncNatives.loomTimeout(r));
        HANDLERS.put("__native_loom_cancel", (r, c) -> AsyncNatives.loomCancel(r));
        HANDLERS.put("__native_loom_sleep", (r, c) -> AsyncNatives.loomSleep(r));
        HANDLERS.put("__native_loom_is_done", (r, c) -> AsyncNatives.loomIsDone(r));
        HANDLERS.put("__native_loom_is_cancelled", (r, c) -> AsyncNatives.loomIsCancelled(r));
        HANDLERS.put("__native_loom_check", (r, c) -> AsyncNatives.loomCheck(r));
        HANDLERS.put("__native_loom_any", (r, c) -> AsyncNatives.loomAny(r));
        HANDLERS.put("__native_loom_settle", (r, c) -> AsyncNatives.loomSettle(r));
        HANDLERS.put("__native_loom_scope_cancel_all", (r, c) -> AsyncNatives.loomScopeCancelAll(r));
        HANDLERS.put("__native_loom_scope_is_cancelled", (r, c) -> AsyncNatives.loomScopeIsCancelled(r));
        HANDLERS.put("__native_loom_scope_get_token", (r, c) -> AsyncNatives.loomScopeGetToken(r));

        // === OS Threads - true parallelism, limited scale ===
        HANDLERS_WITH_NODE.put("__native_os_spawn_cfg", (r, c, n) -> AsyncNatives.osSpawnCfg(r, c, n));
        HANDLERS_WITH_NODE.put("__native_os_join", (r, c, n) -> AsyncNatives.osJoin(r, c, n));
        HANDLERS_WITH_NODE.put("__native_os_join3", (r, c, n) -> AsyncNatives.osJoin3(r, c, n));
        HANDLERS_WITH_NODE.put("__native_os_map", (r, c, n) -> AsyncNatives.osMap(r, c, n));
        HANDLERS_WITH_NODE.put("__native_os_for_each", (r, c, n) -> AsyncNatives.osForEach(r, c, n));
        HANDLERS_WITH_NODE.put("__native_os_filter", (r, c, n) -> AsyncNatives.osFilter(r, c, n));
        HANDLERS_WITH_NODE.put("__native_os_scoped", (r, c, n) -> AsyncNatives.osScoped(r, c, n));

        HANDLERS.put("__native_os_await", (r, c) -> AsyncNatives.osAwait(r));
        HANDLERS.put("__native_os_race", (r, c) -> AsyncNatives.osRace(r));
        HANDLERS.put("__native_os_all", (r, c) -> AsyncNatives.osAll(r));
        HANDLERS.put("__native_os_timeout", (r, c) -> AsyncNatives.osTimeout(r));
        HANDLERS.put("__native_os_cancel", (r, c) -> AsyncNatives.osCancel(r));
        HANDLERS.put("__native_os_sleep", (r, c) -> AsyncNatives.osSleep(r));
        HANDLERS.put("__native_os_is_done", (r, c) -> AsyncNatives.osIsDone(r));
        HANDLERS.put("__native_os_is_cancelled", (r, c) -> AsyncNatives.osIsCancelled(r));
        HANDLERS.put("__native_os_check", (r, c) -> AsyncNatives.osCheck(r));
        HANDLERS.put("__native_os_any", (r, c) -> AsyncNatives.osAny(r));
        HANDLERS.put("__native_os_settle", (r, c) -> AsyncNatives.osSettle(r));

        // === Coop (Cooperative) - single-threaded, deterministic ===
        // Coop runs synchronously on the same thread, so no HANDLERS_WITH_NODE needed
        HANDLERS.put("__native_coop_spawn", (r, c) -> AsyncNatives.coopSpawn(r, c));
        HANDLERS.put("__native_coop_await", (r, c) -> AsyncNatives.coopAwait(r));
        HANDLERS.put("__native_coop_race", (r, c) -> AsyncNatives.coopRace(r));
        HANDLERS.put("__native_coop_all", (r, c) -> AsyncNatives.coopAll(r));
        HANDLERS.put("__native_coop_timeout", (r, c) -> AsyncNatives.coopTimeout(r));
        HANDLERS.put("__native_coop_sleep", (r, c) -> AsyncNatives.coopSleep(r));
        HANDLERS.put("__native_coop_is_done", (r, c) -> AsyncNatives.coopIsDone(r));
        HANDLERS.put("__native_coop_join", (r, c) -> AsyncNatives.coopJoin(r, c));
        HANDLERS.put("__native_coop_join3", (r, c) -> AsyncNatives.coopJoin3(r, c));
        HANDLERS.put("__native_coop_map", (r, c) -> AsyncNatives.coopMap(r, c));
        HANDLERS.put("__native_coop_for_each", (r, c) -> AsyncNatives.coopForEach(r, c));
        HANDLERS.put("__native_coop_filter", (r, c) -> AsyncNatives.coopFilter(r, c));
        HANDLERS.put("__native_coop_any", (r, c) -> AsyncNatives.coopAny(r));
        HANDLERS.put("__native_coop_settle", (r, c) -> AsyncNatives.coopSettle(r));
        HANDLERS.put("__native_coop_scoped", (r, c) -> AsyncNatives.coopScoped(r, c));
    }

    // ==================== Concurrency Primitives ====================

    private static void registerConcurrencyHandlers() {
        // === TVar (STM) ===
        HANDLERS.put("__native_tvar_new", (r, c) -> ConcurrencyNatives.tvarNew(r));
        HANDLERS.put("__native_tvar_deref", (r, c) -> ConcurrencyNatives.tvarDeref(r));
        HANDLERS.put("__native_stm_read", (r, c) -> ConcurrencyNatives.stmRead(r));
        HANDLERS.put("__native_stm_write", (r, c) -> ConcurrencyNatives.stmWrite(r));
        HANDLERS.put("__native_stm_ensure", (r, c) -> ConcurrencyNatives.stmEnsure(r));
        HANDLERS.put("__native_stm_read_cfg", (r, c) -> ConcurrencyNatives.stmRead(r));
        HANDLERS.put("__native_stm_write_cfg", (r, c) -> ConcurrencyNatives.stmWrite(r));
        HANDLERS.put("__native_stm_ensure_cfg", (r, c) -> ConcurrencyNatives.stmEnsure(r));
        HANDLERS.put("__native_stm_seq_read", (r, c) -> ConcurrencyNatives.stmSeqRead(r));
        HANDLERS.put("__native_stm_seq_write", (r, c) -> ConcurrencyNatives.stmSeqWrite(r));

        // === Atom ===
        HANDLERS.put("__native_atom_new", (r, c) -> ConcurrencyNatives.atomNew(r));
        HANDLERS.put("__native_atom_new_validated", (r, c) -> ConcurrencyNatives.atomNewValidated(r));
        HANDLERS.put("__native_atom_deref", (r, c) -> ConcurrencyNatives.atomDeref(r));
        HANDLERS.put("__native_atom_reset", (r, c) -> ConcurrencyNatives.atomReset(r));
        HANDLERS.put("__native_atom_swap", (r, c) -> ConcurrencyNatives.atomSwap(r));
        HANDLERS.put("__native_atom_compare_and_set", (r, c) -> ConcurrencyNatives.atomCompareAndSet(r));

        // === Mutex ===
        HANDLERS.put("__native_mutex_new", (r, c) -> ConcurrencyNatives.mutexNew(r));
        HANDLERS.put("__native_mutex_lock", (r, c) -> ConcurrencyNatives.mutexLock(r));
        HANDLERS.put("__native_mutex_unlock", (r, c) -> ConcurrencyNatives.mutexUnlock(r));
        HANDLERS.put("__native_mutex_try_lock", (r, c) -> ConcurrencyNatives.mutexTryLock(r));

        // === Semaphore ===
        HANDLERS.put("__native_semaphore_new", (r, c) -> ConcurrencyNatives.semaphoreNew(r));
        HANDLERS.put("__native_semaphore_acquire", (r, c) -> ConcurrencyNatives.semaphoreAcquire(r));
        HANDLERS.put("__native_semaphore_release", (r, c) -> ConcurrencyNatives.semaphoreRelease(r));
        HANDLERS.put("__native_semaphore_try_acquire", (r, c) -> ConcurrencyNatives.semaphoreTryAcquire(r));
        HANDLERS.put("__native_semaphore_available", (r, c) -> ConcurrencyNatives.semaphoreAvailable(r));

        // === Latch ===
        HANDLERS.put("__native_latch_new", (r, c) -> ConcurrencyNatives.latchNew(r));
        HANDLERS.put("__native_latch_count_down", (r, c) -> ConcurrencyNatives.latchCountDown(r));
        HANDLERS.put("__native_latch_await", (r, c) -> ConcurrencyNatives.latchAwait(r));
        HANDLERS.put("__native_latch_get_count", (r, c) -> ConcurrencyNatives.latchGetCount(r));

        // === WaitGroup ===
        HANDLERS.put("__native_waitgroup_new", (r, c) -> ConcurrencyNatives.waitgroupNew(r));
        HANDLERS.put("__native_waitgroup_add", (r, c) -> ConcurrencyNatives.waitgroupAdd(r));
        HANDLERS.put("__native_waitgroup_done", (r, c) -> ConcurrencyNatives.waitgroupDone(r));
        HANDLERS.put("__native_waitgroup_wait", (r, c) -> ConcurrencyNatives.waitgroupWait(r));
        HANDLERS.put("__native_waitgroup_count", (r, c) -> ConcurrencyNatives.waitgroupCount(r));

        // === Channel ===
        HANDLERS.put("__native_channel_make", (r, c) -> ConcurrencyNatives.channelMake(r));
        HANDLERS.put("__native_channel_send", (r, c) -> ConcurrencyNatives.channelSend(r));
        HANDLERS.put("__native_channel_receive", (r, c) -> ConcurrencyNatives.channelReceive(r));
        HANDLERS.put("__native_channel_close", (r, c) -> ConcurrencyNatives.channelClose(r));

        // === CancellationToken ===
        HANDLERS.put("__native_cancellation_token_new", (r, c) -> ConcurrencyNatives.cancellationTokenNew(r));
        HANDLERS.put("__native_cancellation_token_cancel", (r, c) -> ConcurrencyNatives.cancellationTokenCancel(r));
        HANDLERS.put("__native_cancellation_token_is_cancelled", (r, c) -> ConcurrencyNatives.cancellationTokenIsCancelled(r));

        // === Agent ===
        HANDLERS.put("__native_agent_new", (r, c) -> ConcurrencyNatives.agentNew(r));
        HANDLERS.put("__native_agent_deref", (r, c) -> ConcurrencyNatives.agentDeref(r));
        HANDLERS.put("__native_agent_send", (r, c) -> ConcurrencyNatives.agentSend(r));
        HANDLERS.put("__native_agent_await", (r, c) -> ConcurrencyNatives.agentAwait(r));
        HANDLERS.put("__native_agent_error", (r, c) -> ConcurrencyNatives.agentError(r));

        // === Additional STM functions ===
        HANDLERS.put("__native_stm_atomic", (r, c) -> ConcurrencyNatives.stmAtomic(r, c));
        HANDLERS.put("__native_stm_modify", (r, c) -> ConcurrencyNatives.stmModify(r, c));
        HANDLERS.put("__native_stm_seq_modify", (r, c) -> ConcurrencyNatives.stmSeqModify(r, c));
        HANDLERS.put("__native_stm_seq_swap", (r, c) -> ConcurrencyNatives.stmSeqSwap(r));
        HANDLERS.put("__native_stm_read_all", (r, c) -> ConcurrencyNatives.stmReadAll(r));
        HANDLERS.put("__native_stm_write_all", (r, c) -> ConcurrencyNatives.stmWriteAll(r));

        // === Additional Atom functions ===
        HANDLERS.put("__native_atom_cas", (r, c) -> ConcurrencyNatives.atomCas(r));
        HANDLERS.put("__native_atom_add_watch", (r, c) -> ConcurrencyNatives.atomAddWatch(r));
        HANDLERS.put("__native_atom_remove_watch", (r, c) -> ConcurrencyNatives.atomRemoveWatch(r));

        // === Additional Mutex functions ===
        HANDLERS.put("__native_mutex_with_lock", (r, c) -> ConcurrencyNatives.mutexWithLock(r, c));
        HANDLERS.put("__native_mutex_is_held_by_current_thread", (r, c) -> ConcurrencyNatives.mutexIsHeldByCurrentThread(r));

        // === Additional Semaphore functions ===
        HANDLERS.put("__native_semaphore_with_permit", (r, c) -> ConcurrencyNatives.semaphoreWithPermit(r, c));
        HANDLERS.put("__native_semaphore_acquire_n", (r, c) -> ConcurrencyNatives.semaphoreAcquireN(r));
        HANDLERS.put("__native_semaphore_release_n", (r, c) -> ConcurrencyNatives.semaphoreReleaseN(r));

        // === Additional Latch functions ===
        HANDLERS.put("__native_latch_await_timeout", (r, c) -> ConcurrencyNatives.latchAwaitTimeout(r));

        // === Additional WaitGroup functions ===
        HANDLERS.put("__native_waitgroup_get_count", (r, c) -> ConcurrencyNatives.waitgroupGetCount(r));
        HANDLERS.put("__native_waitgroup_wait_timeout", (r, c) -> ConcurrencyNatives.waitgroupWaitTimeout(r));

        // === Additional Channel functions ===
        HANDLERS.put("__native_mock_channel_send", (r, c) -> ConcurrencyNatives.mockChannelSend(r));
        HANDLERS.put("__native_mock_channel_receive", (r, c) -> ConcurrencyNatives.mockChannelReceive(r));

        // === Additional CancellationToken functions ===
        HANDLERS.put("__native_cancellation_token_create_child", (r, c) -> ConcurrencyNatives.cancellationTokenCreateChild(r));
        HANDLERS.put("__native_cancellation_token_get_reason", (r, c) -> ConcurrencyNatives.cancellationTokenGetReason(r));

        // === Sequential operations ===
        HANDLERS.put("__native_sequential_map", (r, c) -> ConcurrencyNatives.sequentialMap(r, c));
        HANDLERS.put("__native_sequential_filter", (r, c) -> ConcurrencyNatives.sequentialFilter(r, c));
        HANDLERS.put("__native_sequential_fold", (r, c) -> ConcurrencyNatives.sequentialFold(r, c));
        HANDLERS.put("__native_sequential_reduce", (r, c) -> ConcurrencyNatives.sequentialReduce(r, c));
        HANDLERS.put("__native_sequential_find", (r, c) -> ConcurrencyNatives.sequentialFind(r, c));
        HANDLERS.put("__native_sequential_all", (r, c) -> ConcurrencyNatives.sequentialAll(r, c));
        HANDLERS.put("__native_sequential_any", (r, c) -> ConcurrencyNatives.sequentialAny(r, c));
        HANDLERS.put("__native_sequential_partition", (r, c) -> ConcurrencyNatives.sequentialPartition(r, c));
        HANDLERS.put("__native_sequential_both", (r, c) -> ConcurrencyNatives.sequentialBoth(r, c));

        // === Parallel operations ===
        HANDLERS_WITH_NODE.put("__native_parallel_map", (r, c, n) -> ConcurrencyNatives.parallelMap(r, c, n));
        HANDLERS_WITH_NODE.put("__native_parallel_filter", (r, c, n) -> ConcurrencyNatives.parallelFilter(r, c, n));
        HANDLERS_WITH_NODE.put("__native_parallel_fold", (r, c, n) -> ConcurrencyNatives.parallelFold(r, c, n));
        HANDLERS_WITH_NODE.put("__native_parallel_reduce", (r, c, n) -> ConcurrencyNatives.parallelReduce(r, c, n));
        HANDLERS_WITH_NODE.put("__native_parallel_all", (r, c, n) -> ConcurrencyNatives.parallelAll(r, c, n));
        HANDLERS_WITH_NODE.put("__native_parallel_any", (r, c, n) -> ConcurrencyNatives.parallelAny(r, c, n));

        // === SortedMap additional ===
        HANDLERS.put("__native_sorted_map_min_key", (r, c) ->
            SortedMapNatives.minKey(getSortedMap(r, "m")));
        HANDLERS.put("__native_sorted_map_max_key", (r, c) ->
            SortedMapNatives.maxKey(getSortedMap(r, "m")));

        // === Additional STM ===
        HANDLERS.put("__native_stm_swap", (r, c) -> ConcurrencyNatives.stmSwap(r));
        HANDLERS.put("__native_stm_check", (r, c) -> ConcurrencyNatives.stmCheck(r));
        HANDLERS.put("__native_stm_seq_check", (r, c) -> ConcurrencyNatives.stmSeqCheck(r));
        HANDLERS.put("__native_stm_modify_cfg", (r, c) -> ConcurrencyNatives.stmModifyCfg(r, c));

        // === Additional Atom ===
        HANDLERS.put("__native_atom_swap_with", (r, c) -> ConcurrencyNatives.atomSwapWith(r, c));
        HANDLERS.put("__native_atom_get_and_swap", (r, c) -> ConcurrencyNatives.atomGetAndSwap(r));
        HANDLERS.put("__native_atom_set_validator", (r, c) -> ConcurrencyNatives.atomSetValidator(r));

        // === Additional Semaphore ===
        HANDLERS.put("__native_semaphore_try_acquire_n", (r, c) -> ConcurrencyNatives.semaphoreTryAcquireN(r));

        // === Additional Channel ===
        HANDLERS.put("__native_channel_make_buffered", (r, c) -> ConcurrencyNatives.channelMakeBuffered(r));
        HANDLERS.put("__native_mock_channel_try_send", (r, c) -> ConcurrencyNatives.mockChannelTrySend(r));
        HANDLERS.put("__native_mock_channel_try_recv", (r, c) -> ConcurrencyNatives.mockChannelTryRecv(r));
        HANDLERS.put("__native_mock_channel_recv_any_timeout", (r, c) -> ConcurrencyNatives.mockChannelRecvAnyTimeout(r));
        HANDLERS.put("__native_mock_channel_close", (r, c) -> ConcurrencyNatives.mockChannelClose(r));
        HANDLERS.put("__native_mock_channel_is_closed", (r, c) -> ConcurrencyNatives.mockChannelIsClosed(r));

        // === Additional Agent ===
        HANDLERS.put("__native_agent_restart", (r, c) -> ConcurrencyNatives.agentRestart(r));

        // === Additional Sequential ===
        HANDLERS.put("__native_sequential_for_each", (r, c) -> ConcurrencyNatives.sequentialForEach(r, c));
        HANDLERS.put("__native_sequential_group_by", (r, c) -> ConcurrencyNatives.sequentialGroupBy(r, c));
        HANDLERS.put("__native_sequential_both3", (r, c) -> ConcurrencyNatives.sequentialBoth3(r, c));

        // === Additional Parallel ===
        HANDLERS_WITH_NODE.put("__native_parallel_for_each", (r, c, n) -> ConcurrencyNatives.parallelForEach(r, c, n));
        HANDLERS_WITH_NODE.put("__native_parallel_find", (r, c, n) -> ConcurrencyNatives.parallelFind(r, c, n));
        HANDLERS_WITH_NODE.put("__native_parallel_partition", (r, c, n) -> ConcurrencyNatives.parallelPartition(r, c, n));
        HANDLERS_WITH_NODE.put("__native_parallel_group_by", (r, c, n) -> ConcurrencyNatives.parallelGroupBy(r, c, n));
        HANDLERS_WITH_NODE.put("__native_parallel_both", (r, c, n) -> ConcurrencyNatives.parallelBoth(r, c, n));
        HANDLERS_WITH_NODE.put("__native_parallel_both3", (r, c, n) -> ConcurrencyNatives.parallelBoth3(r, c, n));
        HANDLERS_WITH_NODE.put("__native_parallel_map_cfg", (r, c, n) -> ConcurrencyNatives.parallelMapCfg(r, c, n));
        HANDLERS_WITH_NODE.put("__native_parallel_reduce_cfg", (r, c, n) -> ConcurrencyNatives.parallelReduceCfg(r, c, n));
        HANDLERS_WITH_NODE.put("__native_parallel_fold_cfg", (r, c, n) -> ConcurrencyNatives.parallelFoldCfg(r, c, n));
        HANDLERS_WITH_NODE.put("__native_parallel_filter_cfg", (r, c, n) -> ConcurrencyNatives.parallelFilterCfg(r, c, n));

        // === ForkJoin ===
        HANDLERS_WITH_NODE.put("__native_forkjoin_map", (r, c, n) -> ConcurrencyNatives.forkjoinMap(r, c, n));
        HANDLERS_WITH_NODE.put("__native_forkjoin_filter", (r, c, n) -> ConcurrencyNatives.forkjoinFilter(r, c, n));
        HANDLERS_WITH_NODE.put("__native_forkjoin_reduce", (r, c, n) -> ConcurrencyNatives.forkjoinReduce(r, c, n));
        HANDLERS_WITH_NODE.put("__native_forkjoin_fold", (r, c, n) -> ConcurrencyNatives.forkjoinFold(r, c, n));
        HANDLERS_WITH_NODE.put("__native_forkjoin_all", (r, c, n) -> ConcurrencyNatives.forkjoinAll(r, c, n));
        HANDLERS_WITH_NODE.put("__native_forkjoin_any", (r, c, n) -> ConcurrencyNatives.forkjoinAny(r, c, n));
        HANDLERS_WITH_NODE.put("__native_forkjoin_both", (r, c, n) -> ConcurrencyNatives.forkjoinBoth(r, c, n));

        // === SortedMap from_map ===
        HANDLERS.put("__native_sorted_map_from_map", (r, c) -> {
            // Convert regular map to sorted map - placeholder
            return r.getField(r.getFieldIndex("m"));
        });
    }

    // ==================== Buffer, JSON, HTTP ====================

    private static void registerIOHandlers() {
        // === Buffer ===
        HANDLERS.put("__native_buffer_empty", (r, c) -> IONatives.bufferEmpty(r));
        HANDLERS.put("__native_buffer_new", (r, c) -> IONatives.bufferNew(r));
        HANDLERS.put("__native_buffer_from_bytes", (r, c) -> IONatives.bufferFromBytes(r));
        HANDLERS.put("__native_buffer_from_string", (r, c) -> IONatives.bufferFromString(r));
        HANDLERS.put("__native_buffer_to_bytes", (r, c) -> IONatives.bufferToBytes(r));
        HANDLERS.put("__native_buffer_to_string", (r, c) -> IONatives.bufferToString(r));
        HANDLERS.put("__native_buffer_length", (r, c) -> IONatives.bufferLength(r));
        HANDLERS.put("__native_buffer_position", (r, c) -> IONatives.bufferPosition(r));
        HANDLERS.put("__native_buffer_set_position", (r, c) -> IONatives.bufferSetPosition(r));
        HANDLERS.put("__native_buffer_write_byte", (r, c) -> IONatives.bufferWriteByte(r));
        HANDLERS.put("__native_buffer_read_byte", (r, c) -> IONatives.bufferReadByte(r));
        HANDLERS.put("__native_buffer_write_bytes", (r, c) -> IONatives.bufferWriteBytes(r));
        HANDLERS.put("__native_buffer_read_bytes", (r, c) -> IONatives.bufferReadBytes(r));
        HANDLERS.put("__native_buffer_slice", (r, c) -> IONatives.bufferSlice(r));
        HANDLERS.put("__native_buffer_concat", (r, c) -> IONatives.bufferConcat(r));
        HANDLERS.put("__native_buffer_copy", (r, c) -> IONatives.bufferCopy(r));
        HANDLERS.put("__native_buffer_clear", (r, c) -> IONatives.bufferClear(r));
        HANDLERS.put("__native_buffer_write_string", (r, c) -> IONatives.bufferWriteString(r));
        HANDLERS.put("__native_buffer_write", (r, c) -> IONatives.bufferWrite(r));
        HANDLERS.put("__native_buffer_is_empty", (r, c) -> IONatives.bufferIsEmpty(r));
        HANDLERS.put("__native_buffer_size", (r, c) -> IONatives.bufferSize(r));

        // === JSON ===
        HANDLERS.put("__native_json_serialize", (r, c) -> IONatives.jsonSerialize(r));
        HANDLERS.put("__native_json_parse", (r, c) -> IONatives.jsonParse(r));

        // === HTTP Mock ===
        HANDLERS.put("__native_http_mock_request", (r, c) -> IONatives.httpMockRequest(r));
        HANDLERS.put("__native_http_mock_set_response", (r, c) -> IONatives.httpMockSetResponse(r));
        HANDLERS.put("__native_http_mock_clear", (r, c) -> IONatives.httpMockClear(r));
    }
}
