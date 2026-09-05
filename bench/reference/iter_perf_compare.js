// Node.js: equivalent benchmarks
const N = 10_000_000;

function benchSum() {
  let acc = 0;
  for (let i = 0; i < N; i++) acc += i;
  return acc;
}
function benchFilterFold() {
  const half = Math.trunc(N / 2);
  let acc = 0;
  for (let i = 0; i < N; i++) if (i < half) acc += i;
  return acc;
}
function benchFilterMapFold() {
  const half = Math.trunc(N / 2);
  let acc = 0;
  for (let i = 0; i < N; i++) if (i < half) acc += i * 2;
  return acc;
}
function benchCount() {
  const half = Math.trunc(N / 2);
  let count = 0;
  for (let i = 0; i < N; i++) if (i < half) count++;
  return count;
}
function benchAny() {
  for (let i = 0; i < N; i++) if (i === N - 1) return true;
  return false;
}
function benchFind() {
  for (let i = 0; i < N; i++) if (i === N - 1) return i;
  return -1;
}
function benchFilterCollect() {
  const arr = [];
  for (let i = 0; i < N; i++) if (i % 2 === 0) arr.push(i);
  return arr.length;
}

// Also array-based versions (Node idiomatic)
function benchArrayFilterMapFold() {
  const half = Math.trunc(N / 2);
  const arr = new Array(N);
  for (let i = 0; i < N; i++) arr[i] = i;
  return arr.filter(x => x < half).map(x => x * 2).reduce((a, b) => a + b, 0);
}

function bench(label, fn) {
  for (let i = 0; i < 3; i++) {
    const t0 = performance.now();
    fn();
    const ms = Math.round(performance.now() - t0);
    console.log(`  [warmup] ${label} iter=${i} ${ms}ms`);
  }
  for (let i = 0; i < 5; i++) {
    const t0 = performance.now();
    const r = fn();
    const ms = Math.round(performance.now() - t0);
    console.log(`  [MEASURE] ${label} iter=${i} ${ms}ms (r=${r})`);
  }
}

console.log(`== Node.js Iter Perf N=${N} ==`);
console.log("\n--- HAND LOOP ---");
bench("loop:sum", benchSum);
console.log();
bench("loop:filter+fold", benchFilterFold);
console.log();
bench("loop:filter+map+fold", benchFilterMapFold);
console.log();
bench("loop:count", benchCount);
console.log();
bench("loop:any", benchAny);
console.log();
bench("loop:find", benchFind);
console.log();
bench("loop:filter+collect", benchFilterCollect);

console.log("\n--- ARRAY CHAIN (idiomatic) ---");
bench("array:filter+map+fold", benchArrayFilterMapFold);

console.log("\n== done ==");
