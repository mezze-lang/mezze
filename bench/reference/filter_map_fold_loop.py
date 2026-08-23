"""Python: hand loop baseline."""

def compute(n):
    half = n // 2
    acc = 0
    for i in range(n):
        if i < half:
            acc += i * 2
    return acc

print(f"filter_map_fold 0..1M = {compute(1_000_000)}")
print(f"filter_map_fold 0..10M = {compute(10_000_000)}")
