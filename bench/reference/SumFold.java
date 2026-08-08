public class SumFold {
    static long sumTo(long n) {
        long acc = 0;
        for (long i = 0; i < n; i++) acc += i;
        return acc;
    }

    public static void main(String[] args) {
        System.out.println("sum 0..1M = " + sumTo(1_000_000L));
        System.out.println("sum 0..10M = " + sumTo(10_000_000L));
    }
}
