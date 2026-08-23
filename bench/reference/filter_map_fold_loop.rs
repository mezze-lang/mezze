// Rust: hand loop baseline (no iterators, compiler-fused by construction).
// Compile: rustc -O -o filter_map_fold_rs_loop filter_map_fold_loop.rs

fn compute(n: i64) -> i64 {
    let half = n / 2;
    let mut acc: i64 = 0;
    let mut i: i64 = 0;
    while i < n {
        if i < half {
            acc += i * 2;
        }
        i += 1;
    }
    acc
}

fn main() {
    println!("filter_map_fold 0..1M = {}", compute(1_000_000));
    println!("filter_map_fold 0..10M = {}", compute(10_000_000));
}
