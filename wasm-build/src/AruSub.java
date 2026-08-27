import com.oracle.svm.core.annotate.*;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Web Image cannot construct AtomicReferenceFieldUpdater (reflective
 * field-offset path NPEs), and its VarHandle CAS intrinsics fail to compile
 * ("unhandled compare"). Web Image is single-threaded, so plain reflective
 * field access is sufficient and race-free here.
 */
@TargetClass(AtomicReferenceFieldUpdater.class)
final class Target_java_util_concurrent_atomic_AtomicReferenceFieldUpdater {
    @Substitute
    public static <U, W> AtomicReferenceFieldUpdater<U, W> newUpdater(
            Class<U> tclass, Class<W> vclass, String fieldName) {
        return new ReflectiveUpdater<>(tclass, fieldName);
    }
}

final class ReflectiveUpdater<U, W> extends AtomicReferenceFieldUpdater<U, W> {
    private final Field f;
    ReflectiveUpdater(Class<U> tclass, String field) {
        try { f = tclass.getDeclaredField(field); f.setAccessible(true); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
    @Override public boolean compareAndSet(U obj, W expect, W update) {
        try { if (f.get(obj) != expect) return false; f.set(obj, update); return true; }
        catch (IllegalAccessException e) { throw new RuntimeException(e); }
    }
    @Override public boolean weakCompareAndSet(U obj, W expect, W update) {
        return compareAndSet(obj, expect, update);
    }
    @Override public void set(U obj, W v) {
        try { f.set(obj, v); } catch (IllegalAccessException e) { throw new RuntimeException(e); }
    }
    @Override public void lazySet(U obj, W v) { set(obj, v); }
    @SuppressWarnings("unchecked")
    @Override public W get(U obj) {
        try { return (W) f.get(obj); } catch (IllegalAccessException e) { throw new RuntimeException(e); }
    }
}
