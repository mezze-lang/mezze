// Node: hand loop baseline.

function compute(n) {
  const half = Math.trunc(n / 2);
  let acc = 0;
  for (let i = 0; i < n; i++) {
    if (i < half) acc += i * 2;
  }
  return acc;
}
console.log("filter_map_fold 0..1M = " + compute(1_000_000));
console.log("filter_map_fold 0..10M = " + compute(10_000_000));
