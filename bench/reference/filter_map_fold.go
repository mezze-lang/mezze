// Go: hand loop (Go stdlib has no iterators for arithmetic ranges).
// Compile: go build -o filter_map_fold_go filter_map_fold.go

package main

import "fmt"

func compute(n int64) int64 {
	half := n / 2
	var acc int64
	for i := int64(0); i < n; i++ {
		if i < half {
			acc += i * 2
		}
	}
	return acc
}

func main() {
	fmt.Printf("filter_map_fold 0..1M = %d\n", compute(1_000_000))
	fmt.Printf("filter_map_fold 0..10M = %d\n", compute(10_000_000))
}
