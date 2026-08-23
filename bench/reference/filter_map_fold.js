// Node: idiomatic Array.filter().map().reduce() — allocates 2 intermediate arrays.

function compute(n) {
  const half = Math.trunc(n / 2);
  const arr = new Array(n);
  for (let i = 0; i < n; i++) arr[i] = i;
  return arr
    .filter((x) => x < half)
    .map((x) => x * 2)
    .reduce((acc, x) => acc + x, 0);
}
console.log("filter_map_fold 0..1M = " + compute(1_000_000));
console.log("filter_map_fold 0..10M = " + compute(10_000_000));
