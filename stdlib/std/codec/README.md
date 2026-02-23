# Codec Module - Test Coverage

Test files located in: `interpreter/src/test/kotlin/com/mezze/language/interpreter/`

## Directory Structure

```
std/codec/
├── core.mz              # Generic encoding/decoding framework
├── json.mz              # JSON serialization
└── json/
    └── stream.mz        # Streaming JSON parser
```

## Import Paths

```mezze
use std::codec::core::{Encoder, Decoder, EncodeConfig, DecodeConfig}
use std::codec::json::{Json, parse, to_string, JsonValue}
use std::codec::json::stream::{JsonStream, next_token}
```

## Summary

| Module | Functions | Tested | Coverage |
|--------|-----------|--------|----------|
| core.mz | 7 | 7 | 100% |
| json.mz | 10 | 10 | 100% |
| json/stream.mz | 2 | 2 | 100% |

**Overall: 19/19 functions tested (100%)**

---

## core.mz - Generic Encoding/Decoding
Test file: `CodecModuleTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `default_config` | :white_check_mark: | |
| `empty_path` | :white_check_mark: | |
| `append_path` | :white_check_mark: | |
| `append_index` | :white_check_mark: | |
| `format_path` | :white_check_mark: | |
| `encode_error` | :white_check_mark: | |
| `decode_error` | :white_check_mark: | |

---

## json.mz - JSON Serialization
Test file: `JsonModuleTest.kt`, `JsonCodecInterpreterTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `default_config` | :white_check_mark: | |
| `config` | :white_check_mark: | |
| `serialize_to_string` | :white_check_mark: | |
| `to_string` | :white_check_mark: | |
| `parse` | :white_check_mark: | |
| `parse_str` | :white_check_mark: | |
| `transform_field_name` | :white_check_mark: | |
| `inverse_transform_field_name` | :white_check_mark: | |
| `transform_fields` | :white_check_mark: | |
| `inverse_transform_fields` | :white_check_mark: | |

---

## json/stream.mz - Streaming JSON
Test file: `JsonStreamTest.kt`

| Function | Tested | Notes |
|----------|--------|-------|
| `new_stream` | :white_check_mark: | |
| `next_token` | :white_check_mark: | |

---

## Legend

- :white_check_mark: - Function has test coverage
- :x: - Function needs test or verification
