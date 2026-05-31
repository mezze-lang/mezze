package com.mezze.interpreter.instrumentation;

import com.oracle.truffle.api.Option;
import com.oracle.truffle.api.instrumentation.AllocationEvent;
import com.oracle.truffle.api.instrumentation.AllocationEventFilter;
import com.oracle.truffle.api.instrumentation.AllocationListener;
import com.oracle.truffle.api.instrumentation.TruffleInstrument;
import org.graalvm.options.OptionCategory;
import org.graalvm.options.OptionDescriptors;
import org.graalvm.options.OptionKey;
import org.graalvm.options.OptionStability;

import java.io.PrintStream;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Allocation histogram for Mezze runtime values.
 *
 * Activated by `mez dev --allocations`, which translates to the engine
 * option `mezze.allocations=true` (or directly via
 * `engineBuilder.option("mezze.allocations", "true")`).
 *
 * Why a custom instrument instead of Truffle's built-in `memtracer`:
 * memtracer attributes allocations to source locations via a shadow
 * stack of {@link com.oracle.truffle.api.instrumentation.StandardTags.RootTag}-
 * tagged nodes. Until task #187 makes our nodes report tags, that
 * shadow stack stays empty and memtracer's histogram says zero. This
 * instrument sidesteps that by counting allocations directly by Java
 * class — gives us "how many MezzeRecord vs. MezzeSeq vs. MezzeVariant
 * did this run produce", which is the practical value users want from
 * --allocations today.
 *
 * Output is written to the engine's `out` stream on dispose. DevRunner
 * redirects that stream to .mez/profile/mezze-allocations.txt; user `println` writes
 * directly to System.out from the Console effect handler, so the two
 * channels don't collide.
 */
@TruffleInstrument.Registration(
    id = MezzeAllocationsInstrument.ID,
    name = "Mezze Allocations Tracker",
    version = "1.0"
)
public final class MezzeAllocationsInstrument extends TruffleInstrument {

    public static final String ID = "mezze-allocations";

    @Option(name = "", help = "Enable allocation tracking (default: false).",
            category = OptionCategory.USER, stability = OptionStability.STABLE)
    static final OptionKey<Boolean> ENABLED = new OptionKey<>(false);

    private final ConcurrentHashMap<String, LongAdder> counts = new ConcurrentHashMap<>();
    private Env env;

    @Override
    protected OptionDescriptors getOptionDescriptors() {
        return new MezzeAllocationsInstrumentOptionDescriptors();
    }

    @Override
    protected void onCreate(Env env) {
        this.env = env;
        if (!env.getOptions().get(ENABLED)) {
            return;
        }
        // ANY filter accepts allocations from any language; we filter
        // by Java class on the listener side instead, since "Mezze
        // values" are just instances of MezzeRecord/Seq/Variant in our
        // host JVM regardless of whatever language declares them.
        env.getInstrumenter().attachAllocationListener(
            AllocationEventFilter.ANY,
            new AllocationListener() {
                @Override
                public void onEnter(AllocationEvent event) {
                    // No-op — we tally on return so the value is materialized.
                }

                @Override
                public void onReturnValue(AllocationEvent event) {
                    Object value = event.getValue();
                    if (value == null) return;
                    String key = value.getClass().getSimpleName();
                    counts.computeIfAbsent(key, k -> new LongAdder()).increment();
                }
            }
        );
    }

    @Override
    protected void onDispose(Env env) {
        PrintStream out = new PrintStream(env.out());
        out.println("---- Mezze allocation histogram ----");
        if (counts.isEmpty()) {
            out.println("(no allocations recorded)");
        } else {
            long total = counts.values().stream().mapToLong(LongAdder::sum).sum();
            counts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, LongAdder>>comparingLong(e -> e.getValue().sum()).reversed())
                .forEach(e -> out.printf("  %-20s %,12d%n", e.getKey(), e.getValue().sum()));
            out.printf("  %-20s %,12d%n", "TOTAL", total);
        }
        out.flush();
    }
}
