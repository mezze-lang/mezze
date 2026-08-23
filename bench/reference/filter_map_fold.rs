// Rust: idiomatic iterator chain — compiler fuses to one loop.
// Compile: rustc -O -o filter_map_fold_rs filter_map_fold.rs

fn compute(n: i64) -> i64 {
    let half = n / 2;
    (0..n).filter(|x| *x < half).map(|x| x * 2).sum()
}

fn main() {
    println!("filter_map_fold 0..1M = {}", compute(1_000_000));
    println!("filter_map_fold 0..10M = {}", compute(10_000_000));
}
