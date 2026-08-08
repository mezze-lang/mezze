def sum_to(n):
    acc = 0
    for i in range(n):
        acc += i
    return acc

print(f"sum 0..1M = {sum_to(1_000_000)}")
print(f"sum 0..10M = {sum_to(10_000_000)}")
