# Stdlib Fix Plan - Step by Step

## 🎯 ROOT CAUSE IDENTIFIED
The main issues preventing stdlib loading are:

1. **Circular Dependencies**: `operators` → `abilities` → `primitives` → `operators`
2. **Missing Core Functions**: `not` and `concat` functions are undefined
3. **Syntax Errors**: Type modules use incorrect function definition syntax

## 📋 SYSTEMATIC FIX PLAN

### Phase 1: Fix Core Dependencies (CRITICAL)
- [ ] **Fix 1.1**: Add missing `not` function to `std::core::abilities`
- [ ] **Fix 1.2**: Add missing `concat` function to `std::core::abilities` 
- [ ] **Fix 1.3**: Fix circular dependency by restructuring module loading order

### Phase 2: Fix Syntax Errors (HIGH PRIORITY)
- [ ] **Fix 2.1**: Fix function syntax in `std::types::string.funk`
- [ ] **Fix 2.2**: Fix function syntax in `std::types::int.funk`
- [ ] **Fix 2.3**: Fix function syntax in `std::types::float.funk`
- [ ] **Fix 2.4**: Fix function syntax in `std::types::boolean.funk`
- [ ] **Fix 2.5**: Fix function syntax in `std::string::core.funk`
- [ ] **Fix 2.6**: Fix function syntax in `std::collections::arraylist.funk`

### Phase 3: Test and Validate (VERIFICATION)
- [ ] **Fix 3.1**: Test individual module loading
- [ ] **Fix 3.2**: Test complete stdlib loading
- [ ] **Fix 3.3**: Verify all tests still pass
- [ ] **Fix 3.4**: Remove fallback operators

## 🚀 EXPECTED OUTCOME
After these fixes:
- All 9 stdlib modules should load successfully
- No more circular dependencies
- No more syntax errors
- 100% stdlib-based operation (no fallbacks needed)

## ⏱️ ESTIMATED TIME
- Phase 1: ~15 minutes (critical fixes)
- Phase 2: ~20 minutes (syntax fixes)  
- Phase 3: ~10 minutes (testing)
- **Total: ~45 minutes**