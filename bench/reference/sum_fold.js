function sumTo(n) {
  let acc = 0;
  for (let i = 0; i < n; i++) acc += i;
  return acc;
}
console.log("sum 0..1M = " + sumTo(1_000_000));
console.log("sum 0..10M = " + sumTo(10_000_000));
