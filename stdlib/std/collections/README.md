# Collections Module - Test Coverage

Test files located in: `interpreter/src/test/kotlin/com/mezze/language/interpreter/stdlib/`

## Summary

| Module | Functions | Tested | Coverage |
|--------|-----------|--------|----------|
| array.mz | 34 | 34 | 100% |
| list.mz | 40 | 39 | 98% |
| map.mz | 19 | 19 | 100% |
| set.mz | 23 | 23 | 100% |
| deque.mz | 23 | 23 | 100% |
| buffer.mz | 9 | 9 | 100% |
| bytes.mz | 25 | ? | Needs audit |
| nonemptylist.mz | 28 | ? | Needs audit |
| sorted_map.mz | 17 | ? | Needs audit |
| sorted_set.mz | 17 | ? | Needs audit |

---

## array.mz
Test file: `ArrayModuleInterpreterTest.kt`, `ArrayModuleTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `length` | :white_check_mark: | |
| `get` | :white_check_mark: | |
| `set` | :white_check_mark: | |
| `add` | :white_check_mark: | |
| `remove` | :white_check_mark: | |
| `concat` | :white_check_mark: | |
| `of` | :white_check_mark: | |
| `is_empty` | :white_check_mark: | |
| `map` | :white_check_mark: | |
| `filter` | :white_check_mark: | |
| `fold` | :white_check_mark: | |
| `reduce` | :white_check_mark: | |
| `find` | :white_check_mark: | |
| `contains` | :white_check_mark: | |
| `index_of` | :white_check_mark: | |
| `reverse` | :white_check_mark: | |
| `sort` | :white_check_mark: | |
| `sort_by` | :white_check_mark: | |
| `slice` | :white_check_mark: | |
| `take` | :white_check_mark: | |
| `drop` | :white_check_mark: | |
| `head` | :white_check_mark: | |
| `tail` | :white_check_mark: | |
| `equals` | :white_check_mark: | |
| `zip` | :white_check_mark: | |
| `unzip` | :white_check_mark: | |
| `flatten` | :white_check_mark: | |
| `any` | :white_check_mark: | |
| `all` | :white_check_mark: | |
| `partition` | :white_check_mark: | |
| `find_index` | :white_check_mark: | |
| `last` | :white_check_mark: | |
| `init` | :white_check_mark: | |
| `unique` | :white_check_mark: | |

---

## list.mz
Test file: `ListModuleTest.kt`, `ListInterpreterTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `empty` | :white_check_mark: | |
| `cons` | :white_check_mark: | |
| `push` | :white_check_mark: | |
| `is_empty` | :white_check_mark: | |
| `head` | :white_check_mark: | |
| `tail` | :white_check_mark: | |
| `length` | :white_check_mark: | |
| `uncons` | :white_check_mark: | |
| `singleton` | :white_check_mark: | |
| `prepend` | :white_check_mark: | |
| `append` | :white_check_mark: | |
| `get` | :white_check_mark: | |
| `take` | :white_check_mark: | |
| `drop` | :white_check_mark: | |
| `map` | :white_check_mark: | |
| `filter` | :white_check_mark: | |
| `fold_left` | :white_check_mark: | |
| `fold_right` | :white_check_mark: | |
| `reduce` | :white_check_mark: | Via fold_left test |
| `any` | :white_check_mark: | |
| `all` | :white_check_mark: | |
| `contains` | :white_check_mark: | |
| `reverse` | :white_check_mark: | |
| `concat` | :white_check_mark: | |
| `flat_map` | :white_check_mark: | |
| `zip` | :white_check_mark: | |
| `unzip` | :white_check_mark: | |
| `to_array` | :white_check_mark: | |
| `from_array` | :white_check_mark: | |
| `of_array` | :white_check_mark: | |
| `range` | :white_check_mark: | |
| `repeat` | :white_check_mark: | |
| `intersperse` | :white_check_mark: | |
| `sort` | :white_check_mark: | |
| `sort_by` | :white_check_mark: | |
| `find` | :white_check_mark: | |
| `find_index` | :white_check_mark: | |
| `partition` | :white_check_mark: | |
| `last` | :white_check_mark: | |
| `init` | :white_check_mark: | |

---

## map.mz
Test file: `MapModuleTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `empty` | :white_check_mark: | |
| `insert` | :white_check_mark: | |
| `get` | :white_check_mark: | |
| `remove` | :white_check_mark: | |
| `contains_key` | :white_check_mark: | |
| `size` | :white_check_mark: | |
| `is_empty` | :white_check_mark: | |
| `keys` | :white_check_mark: | |
| `values` | :white_check_mark: | |
| `map_values` | :white_check_mark: | |
| `filter` | :white_check_mark: | |
| `fold` | :white_check_mark: | |
| `from_keys` | :white_check_mark: | |
| `merge` | :white_check_mark: | |
| `merge_with` | :white_check_mark: | |
| `get_or_default` | :white_check_mark: | |
| `update` | :white_check_mark: | |
| `remove_all` | :white_check_mark: | |
| `equals` | :white_check_mark: | |

---

## set.mz
Test file: `SetModuleTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `empty` | :white_check_mark: | |
| `insert` | :white_check_mark: | |
| `remove` | :white_check_mark: | |
| `contains` | :white_check_mark: | |
| `size` | :white_check_mark: | |
| `is_empty` | :white_check_mark: | |
| `union` | :white_check_mark: | |
| `intersection` | :white_check_mark: | |
| `difference` | :white_check_mark: | |
| `is_subset` | :white_check_mark: | |
| `is_superset` | :white_check_mark: | |
| `is_disjoint` | :white_check_mark: | |
| `to_array` | :white_check_mark: | |
| `from_array` | :white_check_mark: | |
| `to_list` | :white_check_mark: | |
| `from_list` | :white_check_mark: | |
| `filter` | :white_check_mark: | |
| `map` | :white_check_mark: | |
| `fold` | :white_check_mark: | |
| `singleton` | :white_check_mark: | |
| `insert_all` | :white_check_mark: | |
| `remove_all` | :white_check_mark: | |
| `equals` | :white_check_mark: | |

---

## deque.mz
Test file: `DequeModuleTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `empty` | :white_check_mark: | |
| `singleton` | :white_check_mark: | |
| `from_list` | :white_check_mark: | |
| `push_front` | :white_check_mark: | |
| `push_back` | :white_check_mark: | |
| `pop_front` | :white_check_mark: | |
| `pop_back` | :white_check_mark: | |
| `peek_front` | :white_check_mark: | |
| `peek_back` | :white_check_mark: | |
| `is_empty` | :white_check_mark: | |
| `length` | :white_check_mark: | |
| `to_list` | :white_check_mark: | |
| `to_array` | :white_check_mark: | |
| `map` | :white_check_mark: | |
| `filter` | :white_check_mark: | |
| `fold` | :white_check_mark: | |
| `reverse` | :white_check_mark: | |
| `concat` | :white_check_mark: | |
| `enqueue` | :white_check_mark: | |
| `dequeue` | :white_check_mark: | |
| `push` | :white_check_mark: | |
| `pop` | :white_check_mark: | |
| `equals` | :white_check_mark: | |

---

## buffer.mz
Test file: `BufferModuleTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `new` | :white_check_mark: | |
| `empty` | :white_check_mark: | |
| `write` | :white_check_mark: | |
| `write_byte` | :white_check_mark: | |
| `write_string` | :white_check_mark: | |
| `to_bytes` | :white_check_mark: | |
| `size` | :white_check_mark: | |
| `is_empty` | :white_check_mark: | |
| `clear` | :white_check_mark: | |

---

## bytes.mz
Test file: `BytesModuleTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `empty` | :x: | Needs verification |
| `from_string` | :x: | Needs verification |
| `from_array` | :x: | Needs verification |
| `from_hex` | :x: | Needs verification |
| `from_base64` | :x: | Needs verification |
| `length` | :x: | Needs verification |
| `is_empty` | :x: | Needs verification |
| `get` | :x: | Needs verification |
| `slice` | :x: | Needs verification |
| `take` | :x: | Needs verification |
| `drop` | :x: | Needs verification |
| `concat` | :x: | Needs verification |
| `concat_all` | :x: | Needs verification |
| `repeat` | :x: | Needs verification |
| `index_of` | :x: | Needs verification |
| `contains` | :x: | Needs verification |
| `starts_with` | :x: | Needs verification |
| `ends_with` | :x: | Needs verification |
| `reverse` | :x: | Needs verification |
| `map` | :x: | Needs verification |
| `fold` | :x: | Needs verification |
| `to_string` | :x: | Needs verification |
| `to_array` | :x: | Needs verification |
| `to_hex` | :x: | Needs verification |
| `to_base64` | :x: | Needs verification |

---

## nonemptylist.mz
Test file: `NonEmptyListModuleTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `singleton` | :x: | Needs verification |
| `from_head_tail` | :x: | Needs verification |
| `head` | :x: | Needs verification |
| `tail` | :x: | Needs verification |
| `length` | :x: | Needs verification |
| `cons` | :x: | Needs verification |
| `append` | :x: | Needs verification |
| `prepend_list` | :x: | Needs verification |
| `append_list` | :x: | Needs verification |
| `get` | :x: | Needs verification |
| `last` | :x: | Needs verification |
| `map` | :x: | Needs verification |
| `flat_map` | :x: | Needs verification |
| `fold_left` | :x: | Needs verification |
| `fold_right` | :x: | Needs verification |
| `reduce` | :x: | Needs verification |
| `any` | :x: | Needs verification |
| `all` | :x: | Needs verification |
| `contains` | :x: | Needs verification |
| `reverse` | :x: | Needs verification |
| `zip` | :x: | Needs verification |
| `intersperse` | :x: | Needs verification |
| `to_list` | :x: | Needs verification |
| `from_list` | :x: | Needs verification |
| `to_array` | :x: | Needs verification |
| `from_array` | :x: | Needs verification |
| `sort` | :x: | Needs verification |
| `sort_by` | :x: | Needs verification |

---

## sorted_map.mz
Test file: `SortedMapModuleTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `empty` | :x: | Needs verification |
| `insert` | :x: | Needs verification |
| `get` | :x: | Needs verification |
| `remove` | :x: | Needs verification |
| `contains_key` | :x: | Needs verification |
| `size` | :x: | Needs verification |
| `is_empty` | :x: | Needs verification |
| `keys` | :x: | Needs verification |
| `values` | :x: | Needs verification |
| `entries` | :x: | Needs verification |
| `min_key` | :x: | Needs verification |
| `max_key` | :x: | Needs verification |
| `min_entry` | :x: | Needs verification |
| `max_entry` | :x: | Needs verification |
| `from_map` | :x: | Needs verification |
| `to_map` | :x: | Needs verification |
| `equals` | :x: | Needs verification |

---

## sorted_set.mz
Test file: `SortedSetModuleTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `empty` | :x: | Needs verification |
| `singleton` | :x: | Needs verification |
| `from_list` | :x: | Needs verification |
| `add` | :x: | Needs verification |
| `remove` | :x: | Needs verification |
| `contains` | :x: | Needs verification |
| `size` | :x: | Needs verification |
| `is_empty` | :x: | Needs verification |
| `union` | :x: | Needs verification |
| `intersect` | :x: | Needs verification |
| `difference` | :x: | Needs verification |
| `min` | :x: | Needs verification |
| `max` | :x: | Needs verification |
| `to_list` | :x: | Needs verification |
| `from_set` | :x: | Needs verification |
| `to_set` | :x: | Needs verification |
| `equals` | :x: | Needs verification |

---

## Legend

- :white_check_mark: - Function has test coverage
- :x: - Function needs test or verification
