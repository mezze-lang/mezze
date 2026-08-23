"""Python: idiomatic sum(map(filter)) via generators."""

def compute(n):
    half = n // 2
    return sum(x * 2 for x in range(n) if x < half)

print(f"filter_map_fold 0..1M = {compute(1_000_000)}")
print(f"filter_map_fold 0..10M = {compute(10_000_000)}")
