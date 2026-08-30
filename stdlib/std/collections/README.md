# Collections Module - Test Coverage

Test files located in: `interpreter/src/test/kotlin/com/mezze/language/interpreter/stdlib/`

## Summary

| Module | Functions | Tested | Coverage |
|--------|-----------|--------|----------|
| seq.mz | 34 | 34 | 100% |
| list.mz | 40 | 40 | 100% |
| map.mz | 19 | 19 | 100% |
| set.mz | 23 | 23 | 100% |
| deque.mz | 23 | 23 | 100% |
| buffer.mz | 9 | 9 | 100% |
| bytes.mz | 25 | 25 | 100% |
| nonemptylist.mz | 28 | 28 | 100% |
| sorted_map.mz | 17 | 17 | 100% |
| sorted_set.mz | 17 | 17 | 100% |

---

## seq.mz
Test file: `SeqModuleInterpreterTest.kt`, `SeqModuleTest.kt`

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
| `to_seq` | :white_check_mark: | |
| `from_seq` | :white_check_mark: | |
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
| `get_or` | :white_check_mark: | |
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
| `to_seq` | :white_check_mark: | |
| `from_seq` | :white_check_mark: | |
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
| `to_seq` | :white_check_mark: | |
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
| `write_str` | :white_check_mark: | |
| `to_bytes` | :white_check_mark: | |
| `size` | :white_check_mark: | |
| `is_empty` | :white_check_mark: | |
| `clear` | :white_check_mark: | |

---

## bytes.mz
Test file: `BytesModuleTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `empty` | :white_check_mark: | |
| `from_str` | :white_check_mark: | |
| `from_seq` | :white_check_mark: | |
| `from_hex` | :white_check_mark: | |
| `from_base64` | :white_check_mark: | |
| `length` | :white_check_mark: | |
| `is_empty` | :white_check_mark: | |
| `get` | :white_check_mark: | |
| `slice` | :white_check_mark: | |
| `take` | :white_check_mark: | |
| `drop` | :white_check_mark: | |
| `concat` | :white_check_mark: | |
| `concat_all` | :white_check_mark: | |
| `repeat` | :white_check_mark: | |
| `index_of` | :white_check_mark: | |
| `contains` | :white_check_mark: | |
| `starts_with` | :white_check_mark: | |
| `ends_with` | :white_check_mark: | |
| `reverse` | :white_check_mark: | |
| `map` | :white_check_mark: | |
| `fold` | :white_check_mark: | |
| `to_string` | :white_check_mark: | |
| `to_seq` | :white_check_mark: | |
| `to_hex` | :white_check_mark: | |
| `to_base64` | :white_check_mark: | |

---

## nonemptylist.mz
Test file: `NonEmptyListModuleTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `singleton` | :white_check_mark: | |
| `from_head_tail` | :white_check_mark: | |
| `head` | :white_check_mark: | |
| `tail` | :white_check_mark: | |
| `length` | :white_check_mark: | |
| `cons` | :white_check_mark: | |
| `append` | :white_check_mark: | |
| `prepend_list` | :white_check_mark: | |
| `append_list` | :white_check_mark: | |
| `get` | :white_check_mark: | |
| `last` | :white_check_mark: | |
| `map` | :white_check_mark: | |
| `flat_map` | :white_check_mark: | |
| `fold_left` | :white_check_mark: | |
| `fold_right` | :white_check_mark: | |
| `reduce` | :white_check_mark: | |
| `any` | :white_check_mark: | |
| `all` | :white_check_mark: | |
| `contains` | :white_check_mark: | |
| `reverse` | :white_check_mark: | |
| `zip` | :white_check_mark: | |
| `intersperse` | :white_check_mark: | |
| `to_list` | :white_check_mark: | |
| `from_list` | :white_check_mark: | |
| `to_seq` | :white_check_mark: | |
| `from_seq` | :white_check_mark: | |
| `sort` | :white_check_mark: | |
| `sort_by` | :white_check_mark: | |

---

## sorted_map.mz
Test file: `SortedMapModuleTest.kt`

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
| `entries` | :white_check_mark: | |
| `min_key` | :white_check_mark: | |
| `max_key` | :white_check_mark: | |
| `min_entry` | :white_check_mark: | |
| `max_entry` | :white_check_mark: | |
| `from_map` | :white_check_mark: | |
| `to_map` | :white_check_mark: | |
| `equals` | :white_check_mark: | |

---

## sorted_set.mz
Test file: `SortedSetModuleTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `empty` | :white_check_mark: | |
| `singleton` | :white_check_mark: | |
| `from_list` | :white_check_mark: | |
| `add` | :white_check_mark: | |
| `remove` | :white_check_mark: | |
| `contains` | :white_check_mark: | |
| `size` | :white_check_mark: | |
| `is_empty` | :white_check_mark: | |
| `union` | :white_check_mark: | |
| `intersect` | :white_check_mark: | |
| `difference` | :white_check_mark: | |
| `min` | :white_check_mark: | |
| `max` | :white_check_mark: | |
| `to_list` | :white_check_mark: | |
| `from_set` | :white_check_mark: | |
| `to_set` | :white_check_mark: | |
| `equals` | :white_check_mark: | |

---

## Legend

- :white_check_mark: - Function has test coverage
- :x: - Function needs test or verification
