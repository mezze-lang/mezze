import com.oracle.svm.core.annotate.*;

/**
 * Web Image (--tool:svm-wasm) cannot create AtomicReferenceFieldUpdater, which
 * kotlin.SafePublicationLazyImpl (LazyThreadSafetyMode.PUBLICATION) needs in its
 * static init. kotlinx-serialization's generated $childSerializers array uses
 * PUBLICATION and is built in <clinit>, so every @Serializable class fails to
 * load.
 *
 * SYNCHRONIZED and NONE both work under Web Image, so redirect the
 * mode-taking overload to the default (SYNCHRONIZED) one. Semantics are
 * compatible: SYNCHRONIZED is strictly stronger than PUBLICATION.
 */
@TargetClass(className = "kotlin.LazyKt__LazyJVMKt")
final class Target_kotlin_LazyKt__LazyJVMKt {
    @Substitute
    public static <T> kotlin.Lazy<T> lazy(kotlin.LazyThreadSafetyMode mode,
                                          kotlin.jvm.functions.Function0<? extends T> initializer) {
        return kotlin.LazyKt.lazy(initializer);
    }
}
