package com.mezze.interpreter.v2.benchmark;

import com.mezze.interpreter.v2.values.MezzeRecord;
import com.mezze.interpreter.v2.values.MezzeRecord.FieldLocation;
import com.mezze.interpreter.v2.values.MezzeRecord.FieldLocation.StorageType;
import com.mezze.interpreter.v2.values.MezzeRecord.Shape;
import com.mezze.interpreter.v2.values.MezzeVariant;

import org.junit.jupiter.api.Test;

import java.util.Random;

/**
 * Benchmark suite for MezzeRecord and MezzeVariant operations.
 *
 * Measures performance of optimizations:
 * - Record CoW (Copy-on-Write) with structural sharing
 * - Record typed storage (long[], double[], Object[])
 * - Variant single-value specialization (no Object[] allocation)
 *
 * Run with:
 *   ./gradlew :interpreter:test --tests "*.RecordVariantBenchmark.*" -i
 */
public class RecordVariantBenchmark {

    private static final int WARMUP_ITERATIONS = 500;
    private static final int BENCHMARK_ITERATIONS = 5000;
    private static final Random RANDOM = new Random(42);

    @Test
    public void runAllBenchmarks() {
        printHeader("RECORD & VARIANT BENCHMARK SUITE");
        printConfig();

        benchmarkRecordCreation();
        benchmarkRecordFieldAccess();
        benchmarkRecordUpdate();
        benchmarkRecordChainedUpdate();
        benchmarkRecordBatchUpdate();
        benchmarkRecordStructuralSharing();
        benchmarkVariantCreation();
        benchmarkVariantPayloadAccess();
        benchmarkVariantPatternMatching();
    }

    // ==================== RECORD CREATION ====================

    @Test
    public void benchmarkRecordCreation() {
        printHeader("Record Creation");

        // Small record (3 fields, all objects)
        benchmark("create small record (3 fields)", () -> {
            MezzeRecord rec = MezzeRecord.fromFields(
                new String[]{"x", "y", "z"},
                new Object[]{1L, 2L, 3L}
            );
            return rec.getFieldCount();
        });

        // Medium record (10 fields)
        benchmark("create medium record (10 fields)", () -> {
            MezzeRecord rec = MezzeRecord.fromFields(
                new String[]{"f0", "f1", "f2", "f3", "f4", "f5", "f6", "f7", "f8", "f9"},
                new Object[]{0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L}
            );
            return rec.getFieldCount();
        });

        // Typed storage record (mixed types)
        String[] names = {"intField", "floatField", "strField"};
        FieldLocation[] locations = {
            new FieldLocation(StorageType.LONG, 0),
            new FieldLocation(StorageType.DOUBLE, 0),
            new FieldLocation(StorageType.OBJECT, 0)
        };
        Shape typedShape = new Shape(names, locations);

        benchmark("create typed record (long, double, object)", () -> {
            MezzeRecord rec = new MezzeRecord(typedShape,
                new long[]{42L},
                new double[]{3.14},
                new Object[]{"hello"});
            return rec.getFieldCount();
        });

        System.out.println();
    }

    // ==================== RECORD FIELD ACCESS ====================

    @Test
    public void benchmarkRecordFieldAccess() {
        printHeader("Record Field Access");

        // Setup records
        MezzeRecord smallRec = MezzeRecord.fromFields(
            new String[]{"x", "y", "z"},
            new Object[]{1L, 2L, 3L}
        );

        String[] names = {"intField", "floatField", "strField"};
        FieldLocation[] locations = {
            new FieldLocation(StorageType.LONG, 0),
            new FieldLocation(StorageType.DOUBLE, 0),
            new FieldLocation(StorageType.OBJECT, 0)
        };
        Shape typedShape = new Shape(names, locations);
        MezzeRecord typedRec = new MezzeRecord(typedShape,
            new long[]{42L},
            new double[]{3.14},
            new Object[]{"hello"});

        // Field access by index
        benchmark("getField(index) - 100 accesses", () -> {
            long sum = 0;
            for (int i = 0; i < 100; i++) {
                sum += (Long) smallRec.getField(i % 3);
            }
            return sum;
        });

        // Field access by name (slow path)
        benchmark("getField(name) - 100 accesses", () -> {
            long sum = 0;
            for (int i = 0; i < 100; i++) {
                Object val = smallRec.getField("x");
                sum += val != null ? (Long) val : 0;
            }
            return sum;
        });

        // Typed field access (unboxed long)
        benchmark("getLongField - 100 accesses", () -> {
            long sum = 0;
            for (int i = 0; i < 100; i++) {
                sum += typedRec.getLongField(0);
            }
            return sum;
        });

        // Typed field access (unboxed double)
        benchmark("getDoubleField - 100 accesses", () -> {
            double sum = 0;
            for (int i = 0; i < 100; i++) {
                sum += typedRec.getDoubleField(0);
            }
            return (long) sum;
        });

        System.out.println();
    }

    // ==================== RECORD UPDATE ====================

    @Test
    public void benchmarkRecordUpdate() {
        printHeader("Record Update (Single Field)");

        MezzeRecord rec = MezzeRecord.fromFields(
            new String[]{"x", "y", "z"},
            new Object[]{1L, 2L, 3L}
        );

        // Single field update
        benchmark("withField (single update)", () -> {
            MezzeRecord updated = rec.withField(0, 100L);
            return (Long) updated.getField(0);
        });

        // Multiple independent updates
        benchmark("withField x10 (independent)", () -> {
            long sum = 0;
            for (int i = 0; i < 10; i++) {
                MezzeRecord updated = rec.withField(0, (long) i);
                sum += (Long) updated.getField(0);
            }
            return sum;
        });

        System.out.println();
    }

    // ==================== RECORD CHAINED UPDATE ====================

    @Test
    public void benchmarkRecordChainedUpdate() {
        printHeader("Record Chained Update");

        MezzeRecord rec = MezzeRecord.fromFields(
            new String[]{"a", "b", "c", "d", "e"},
            new Object[]{0L, 0L, 0L, 0L, 0L}
        );

        // Chained updates (simulates {...{...rec, a=1}, b=2})
        benchmark("chain of 3 updates", () -> {
            MezzeRecord r1 = rec.withField(0, 1L);
            MezzeRecord r2 = r1.withField(1, 2L);
            MezzeRecord r3 = r2.withField(2, 3L);
            return (Long) r3.getField(0) + (Long) r3.getField(1) + (Long) r3.getField(2);
        });

        benchmark("chain of 5 updates", () -> {
            MezzeRecord r = rec;
            for (int i = 0; i < 5; i++) {
                r = r.withField(i, (long) (i + 1));
            }
            return (Long) r.getField(4);
        });

        System.out.println();
    }

    // ==================== RECORD BATCH UPDATE ====================

    @Test
    public void benchmarkRecordBatchUpdate() {
        printHeader("Record Batch Update (withFields)");

        MezzeRecord rec = MezzeRecord.fromFields(
            new String[]{"a", "b", "c", "d", "e"},
            new Object[]{0L, 0L, 0L, 0L, 0L}
        );

        int[] indices2 = {0, 1};
        Object[] values2 = {10L, 20L};

        int[] indices3 = {0, 2, 4};
        Object[] values3 = {10L, 30L, 50L};

        int[] indices5 = {0, 1, 2, 3, 4};
        Object[] values5 = {10L, 20L, 30L, 40L, 50L};

        // Batch update 2 fields
        benchmark("withFields (2 fields)", () -> {
            MezzeRecord updated = rec.withFields(indices2, values2);
            return (Long) updated.getField(0) + (Long) updated.getField(1);
        });

        // Batch update 3 fields
        benchmark("withFields (3 fields)", () -> {
            MezzeRecord updated = rec.withFields(indices3, values3);
            return (Long) updated.getField(0) + (Long) updated.getField(2) + (Long) updated.getField(4);
        });

        // Batch update 5 fields (all)
        benchmark("withFields (5 fields - all)", () -> {
            MezzeRecord updated = rec.withFields(indices5, values5);
            return (Long) updated.getField(4);
        });

        // Compare: chained vs batch
        benchmark("compare: 3 chained withField", () -> {
            MezzeRecord r1 = rec.withField(0, 10L);
            MezzeRecord r2 = r1.withField(2, 30L);
            MezzeRecord r3 = r2.withField(4, 50L);
            return (Long) r3.getField(0) + (Long) r3.getField(2) + (Long) r3.getField(4);
        });

        System.out.println();
    }

    // ==================== RECORD STRUCTURAL SHARING ====================

    @Test
    public void benchmarkRecordStructuralSharing() {
        printHeader("Record Structural Sharing");

        // Setup typed record with 3 storage types
        String[] names = {"lng", "dbl", "obj"};
        FieldLocation[] locations = {
            new FieldLocation(StorageType.LONG, 0),
            new FieldLocation(StorageType.DOUBLE, 0),
            new FieldLocation(StorageType.OBJECT, 0)
        };
        Shape shape = new Shape(names, locations);
        MezzeRecord typedRec = new MezzeRecord(shape,
            new long[]{42L},
            new double[]{3.14},
            new Object[]{"hello"});

        // Update only long field (should share double[] and Object[])
        benchmark("update long only (share double, object)", () -> {
            MezzeRecord updated = typedRec.withField(0, 100L);
            return updated.getLongField(0);
        });

        // Update only double field
        benchmark("update double only (share long, object)", () -> {
            MezzeRecord updated = typedRec.withField(1, 2.71);
            return (long) updated.getDoubleField(0);
        });

        // Update only object field
        benchmark("update object only (share long, double)", () -> {
            MezzeRecord updated = typedRec.withField(2, "world");
            return ((String) updated.getObjectField(0)).length();
        });

        System.out.println();
    }

    // ==================== VARIANT CREATION ====================

    @Test
    public void benchmarkVariantCreation() {
        printHeader("Variant Creation");

        MezzeVariant.TagRegistry registry = new MezzeVariant.TagRegistry(
            new String[]{"None", "Some", "Pair"}
        );

        // No payload (None)
        benchmark("create None (no payload)", () -> {
            MezzeVariant v = MezzeVariant.noPayload(registry, 0);
            return v.getTag();
        });

        // Single payload - optimized path
        benchmark("create Some (single payload, optimized)", () -> {
            MezzeVariant v = MezzeVariant.singlePayload(registry, 1, 42L);
            return v.getTag();
        });

        // Multi payload
        benchmark("create Pair (multi payload)", () -> {
            MezzeVariant v = MezzeVariant.multiPayload(registry, 2,
                new Object[]{1L, 2L});
            return v.getTag();
        });

        // Helper methods
        benchmark("MezzeVariant.none() cached", () -> {
            MezzeVariant v = MezzeVariant.none();
            return v.getTag();
        });

        benchmark("MezzeVariant.some(value)", () -> {
            MezzeVariant v = MezzeVariant.some(42L);
            return v.getTag();
        });

        benchmark("MezzeVariant.ok(value)", () -> {
            MezzeVariant v = MezzeVariant.ok("success");
            return v.getTag();
        });

        benchmark("MezzeVariant.err(error)", () -> {
            MezzeVariant v = MezzeVariant.err("failure");
            return v.getTag();
        });

        System.out.println();
    }

    // ==================== VARIANT PAYLOAD ACCESS ====================

    @Test
    public void benchmarkVariantPayloadAccess() {
        printHeader("Variant Payload Access");

        MezzeVariant.TagRegistry registry = new MezzeVariant.TagRegistry(
            new String[]{"Some", "Pair"}
        );

        MezzeVariant singlePayload = MezzeVariant.singlePayload(registry, 0, 42L);
        MezzeVariant multiPayload = MezzeVariant.multiPayload(registry, 1,
            new Object[]{1L, 2L});

        // Single payload access (optimized)
        benchmark("getPayloadValue (single, optimized)", () -> {
            long sum = 0;
            for (int i = 0; i < 100; i++) {
                sum += (Long) singlePayload.getPayloadValue();
            }
            return sum;
        });

        benchmark("getPayloadField(0) (single, optimized)", () -> {
            long sum = 0;
            for (int i = 0; i < 100; i++) {
                sum += (Long) singlePayload.getPayloadField(0);
            }
            return sum;
        });

        // Multi payload access
        benchmark("getPayloadField (multi)", () -> {
            long sum = 0;
            for (int i = 0; i < 100; i++) {
                sum += (Long) multiPayload.getPayloadField(0);
                sum += (Long) multiPayload.getPayloadField(1);
            }
            return sum;
        });

        // getPayload() - creates array for single
        benchmark("getPayload() (single, array creation)", () -> {
            int count = 0;
            for (int i = 0; i < 100; i++) {
                count += singlePayload.getPayload().length;
            }
            return count;
        });

        System.out.println();
    }

    // ==================== VARIANT PATTERN MATCHING ====================

    @Test
    public void benchmarkVariantPatternMatching() {
        printHeader("Variant Pattern Matching");

        MezzeVariant.TagRegistry registry = new MezzeVariant.TagRegistry(
            new String[]{"None", "Some", "Other"}
        );

        MezzeVariant none = MezzeVariant.noPayload(registry, 0);
        MezzeVariant some = MezzeVariant.singlePayload(registry, 1, 42L);
        MezzeVariant[] variants = {none, some, some, none, some};

        // Tag comparison (fast path)
        benchmark("match by tag (switch on int)", () -> {
            int noneCount = 0, someCount = 0;
            for (int i = 0; i < 100; i++) {
                MezzeVariant v = variants[i % 5];
                switch (v.getTag()) {
                    case 0 -> noneCount++;
                    case 1 -> someCount++;
                }
            }
            return noneCount + someCount;
        });

        // Constructor name comparison (slow path)
        benchmark("match by name (string compare)", () -> {
            int noneCount = 0, someCount = 0;
            for (int i = 0; i < 100; i++) {
                MezzeVariant v = variants[i % 5];
                String name = v.getConstructorName();
                if ("None".equals(name)) noneCount++;
                else if ("Some".equals(name)) someCount++;
            }
            return noneCount + someCount;
        });

        // isSome/isNone helpers
        benchmark("isSome/isNone helpers", () -> {
            int someCount = 0;
            for (int i = 0; i < 100; i++) {
                MezzeVariant v = variants[i % 5];
                if (v.isSome()) someCount++;
            }
            return someCount;
        });

        // Extract payload in pattern match
        benchmark("match Some { val } extract", () -> {
            long sum = 0;
            for (int i = 0; i < 100; i++) {
                MezzeVariant v = variants[i % 5];
                if (v.getTag() == 1) {
                    // Simulates: Some { val } -> use val
                    sum += (Long) v.getPayloadValue();
                }
            }
            return sum;
        });

        System.out.println();
    }

    // ==================== HELPER METHODS ====================

    private static void benchmark(String name, java.util.function.LongSupplier operation) {
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            operation.getAsLong();
        }

        // Benchmark
        long start = System.nanoTime();
        long result = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            result += operation.getAsLong();
        }
        long elapsed = System.nanoTime() - start;

        double avgNs = (double) elapsed / BENCHMARK_ITERATIONS;
        double avgUs = avgNs / 1000.0;
        double totalMs = elapsed / 1_000_000.0;

        System.out.printf("  %-45s %8.2f us/op  (%6.2f ms total)%n",
                name, avgUs, totalMs);
    }

    private static void printHeader(String title) {
        System.out.println();
        System.out.println("=".repeat(70));
        System.out.println(" " + title);
        System.out.println("=".repeat(70));
        System.out.println();
    }

    private static void printConfig() {
        System.out.printf("Warmup iterations: %d%n", WARMUP_ITERATIONS);
        System.out.printf("Benchmark iterations: %d%n", BENCHMARK_ITERATIONS);
        System.out.printf("Java version: %s%n", System.getProperty("java.version"));
        System.out.println();
    }
}
