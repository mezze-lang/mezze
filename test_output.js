// Funk Runtime Library - Complete Implementation
const FunkRuntime = {
    // Variant construction and matching
    variant: (constructor, data = null) => ({
        __type: 'variant',
        constructor,
        data,
        toString: () => data ? constructor + '(' + JSON.stringify(data) + ')' : constructor
    }),
    
    // Record operations
    record: (fields) => ({
        __type: 'record',
        ...fields,
        toString: () => '{' + Object.entries(fields).map(([k,v]) => k + ': ' + v).join(', ') + '}'
    }),
    
    updateRecord: (record, updates) => ({
        ...record,
        ...updates
    }),
    
    // Pattern matching support
    match: (value, patterns) => {
        for (const [pattern, handler] of patterns) {
            const result = FunkRuntime.matchPattern(value, pattern);
            if (result.matched) {
                return handler(result.bindings);
            }
        }
        throw new Error('Non-exhaustive pattern match for value: ' + JSON.stringify(value));
    },
    
    matchPattern: (value, pattern) => {
        if (typeof pattern === 'string') {
            // Variable pattern - always matches
            return { matched: true, bindings: { [pattern]: value } };
        }
        
        if (typeof pattern === 'object' && pattern.__patternType) {
            switch (pattern.__patternType) {
                case 'variant':
                    if (value.__type === 'variant' && value.constructor === pattern.constructor) {
                        if (pattern.dataPattern) {
                            const dataResult = FunkRuntime.matchPattern(value.data, pattern.dataPattern);
                            return dataResult;
                        }
                        return { matched: true, bindings: {} };
                    }
                    return { matched: false, bindings: {} };
                    
                case 'record':
                    if (value.__type === 'record') {
                        let bindings = {};
                        for (const [field, fieldPattern] of Object.entries(pattern.fields)) {
                            const fieldResult = FunkRuntime.matchPattern(value[field], fieldPattern);
                            if (!fieldResult.matched) {
                                return { matched: false, bindings: {} };
                            }
                            bindings = { ...bindings, ...fieldResult.bindings };
                        }
                        return { matched: true, bindings };
                    }
                    return { matched: false, bindings: {} };
                    
                case 'literal':
                    return { 
                        matched: value === pattern.value, 
                        bindings: {} 
                    };
                    
                default:
                    return { matched: false, bindings: {} };
            }
        }
        
        // Literal pattern
        return { matched: value === pattern, bindings: {} };
    },
    
    // Function operations
    curry: (fn, arity) => {
        return function curried(...args) {
            if (args.length >= arity) {
                return fn.apply(this, args);
            }
            return (...nextArgs) => curried(...args, ...nextArgs);
        };
    },
    
    // Arithmetic operations
    add: (a, b) => a + b,
    subtract: (a, b) => a - b,
    multiply: (a, b) => a * b,
    divide: (a, b) => a / b,
    
    // String operations
    concat: (a, b) => a + b,
    
    // Comparison operations
    equals: (a, b) => a === b,
    notEquals: (a, b) => a !== b,
    lessThan: (a, b) => a < b,
    lessThanOrEqual: (a, b) => a <= b,
    greaterThan: (a, b) => a > b,
    greaterThanOrEqual: (a, b) => a >= b,
    
    // Boolean operations
    and: (a, b) => a && b,
    or: (a, b) => a || b,
    not: (a) => !a,
    
    // Utility functions
    toString: (value) => {
        if (value && typeof value.toString === 'function') {
            return value.toString();
        }
        return String(value);
    },
    
    // Error handling
    error: (message) => {
        throw new Error(message);
    }
};

const mod_utils = "mod";
const add = (param) => FunkRuntime.add(x, y);
const result = add(FunkRuntime.record({ x: 5, y: 3 }));

// Export all bindings for inspection
if (typeof module !== 'undefined' && module.exports) {
  module.exports.mod_utils = mod_utils;
  module.exports.add = add;
  module.exports.result = result;
}
