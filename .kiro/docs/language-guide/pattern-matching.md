# Pattern Matching in Funk

Pattern matching is one of Funk's most powerful features, allowing you to destructure data and control program flow in an elegant, type-safe way.

## Basic Match Expressions

### Syntax
```funk
match expression is
  pattern1 -> result1
  pattern2 -> result2
  _ -> default_result
```

### Simple Example
```funk
type Color = | Red, Green, Blue |

let describe = {color: Color} -> match color is
  Red -> "The color of fire"
  Green -> "The color of grass"
  Blue -> "The color of sky"
```

## Pattern Types

### 1. Constructor Patterns
Match variant constructors:

```funk
type Shape = | Circle, Square, Triangle |

let sides = {shape: Shape} -> match shape is
  Circle -> 0      # Infinite sides, but we'll say 0
  Square -> 4
  Triangle -> 3
```

### 2. Record Patterns
Destructure record data:

```funk
type Person = | Person { name: String, age: Int } |

let greet = {person: Person} -> match person is
  Person { name, age } -> "Hello " ++ name ++ ", age " ++ intToString {val = age}
```

### 3. Wildcard Patterns
Catch-all pattern:

```funk
type Grade = | A, B, C, D, F |

let passed = {grade: Grade} -> match grade is
  A -> true
  B -> true
  C -> true
  _ -> false  # Matches D and F
```

## Multiple Patterns

Use `|` to match multiple patterns with the same result:

```funk
type Status = | Success, Info, Warning, Error, Critical |

let isOk = {status: Status} -> match status is
  Success | Info -> true
  Warning -> "maybe"
  Error | Critical -> false
```

### Complex Multiple Patterns
```funk
type Weather = | Sunny { temp: Int }, Rainy { temp: Int }, Cloudy |

let comfortable = {weather: Weather} -> match weather is
  Sunny { temp } | Rainy { temp } if temp >= 20 && temp <= 25 -> "Perfect!"
  Sunny { temp } | Rainy { temp } -> "Okay"
  Cloudy -> "Meh"
```

## Guard Conditions

Add conditions to patterns using `if`:

```funk
type Temperature = | Hot { degrees: Int }, Cold { degrees: Int } |

let comfort = {temp: Temperature} -> match temp is
  Hot { degrees } if degrees > 100 -> "Too hot!"
  Hot { degrees } if degrees > 80 -> "Very hot"
  Hot { degrees } -> "Warm"
  Cold { degrees } if degrees < -10 -> "Freezing!"
  Cold { degrees } -> "Cool"
```

### Guards with Logical Operators
```funk
type Score = | Score { points: Int } |

let grade = {score: Score} -> match score is
  Score { points } if points >= 90 && points <= 100 -> "A"
  Score { points } if points >= 80 && points < 90 -> "B"
  Score { points } if points >= 70 && points < 80 -> "C"
  Score { points } if points >= 60 && points < 70 -> "D"
  _ -> "F"
```

### Guards with Function Calls
```funk
let isEven = {x: Int} -> x % 2 == 0

type Number = | Num { value: Int } |

let classify = {num: Number} -> match num is
  Num { value } if isEven {x = value} -> "Even number"
  Num { value } -> "Odd number"
```

## Exhaustiveness Checking

Funk ensures your match expressions handle all possible cases:

### ✅ Exhaustive (Good)
```funk
type Bool = | True, False |

let not = {b: Bool} -> match b is
  True -> False
  False -> True
```

### ❌ Non-exhaustive (Error)
```funk
type Color = | Red, Green, Blue |

# This will cause a compile error!
let primary = {color: Color} -> match color is
  Red -> true
  Blue -> true
  # Missing Green case!
```

### ✅ Fixed with Wildcard
```funk
type Color = | Red, Green, Blue |

let primary = {color: Color} -> match color is
  Red -> true
  Blue -> true
  _ -> false  # Covers Green
```

## Advanced Patterns

### Nested Pattern Matching
```funk
type Result = | Success { data: { value: Int } }, Error { code: Int } |

let process = {result: Result} -> match result is
  Success { data } -> match data is
    { value } if value > 0 -> "Positive: " ++ intToString {val = value}
    { value } -> "Non-positive: " ++ intToString {val = value}
  Error { code } -> "Error code: " ++ intToString {val = code}
```

### Pattern Matching in Function Parameters
```funk
type Point = | Point { x: Int, y: Int } |

# Direct pattern matching in parameter
let distance = {Point { x, y }} -> 
  intToString {val = x * x + y * y}  # Simplified distance
```

### Guards with Complex Conditions
```funk
type Event = | Click { x: Int, y: Int }, Drag { startX: Int, startY: Int, endX: Int, endY: Int } |

let eventType = {event: Event} -> match event is
  Click { x, y } if x >= 0 && y >= 0 && x <= 100 && y <= 100 -> "Button click"
  Click { x, y } -> "Click at (" ++ intToString {val = x} ++ ", " ++ intToString {val = y} ++ ")"
  Drag { startX, startY, endX, endY } if startX == endX && startY == endY -> "No movement"
  Drag { startX, startY, endX, endY } -> "Dragged from (" ++ intToString {val = startX} ++ ", " ++ intToString {val = startY} ++ ")"
```

## Best Practices

### 1. Use Specific Patterns First
```funk
# Good: Most specific cases first
type Priority = | Low, Medium, High, Critical |

let urgency = {priority: Priority} -> match priority is
  Critical -> "URGENT!"
  High -> "Important"
  Medium -> "Normal"
  Low -> "When possible"
```

### 2. Prefer Multiple Patterns Over Repeated Logic
```funk
# Good: Group similar cases
type HttpStatus = | Ok, Created, NotFound, ServerError, BadRequest |

let isSuccess = {status: HttpStatus} -> match status is
  Ok | Created -> true
  NotFound | ServerError | BadRequest -> false
```

### 3. Use Guards for Complex Conditions
```funk
# Good: Clear guard conditions
type User = | User { age: Int, premium: Boolean } |

let canAccess = {user: User} -> match user is
  User { age, premium } if age >= 18 && premium -> "Full access"
  User { age, premium } if age >= 18 -> "Basic access"
  User { age, premium } -> "Restricted access"
```

### 4. Handle Edge Cases Explicitly
```funk
# Good: Explicit handling of edge cases
type Division = | Result { numerator: Int, denominator: Int } |

let divide = {div: Division} -> match div is
  Result { numerator, denominator } if denominator == 0 -> "Cannot divide by zero"
  Result { numerator, denominator } -> intToString {val = numerator / denominator}
```

## Common Patterns

### Option/Maybe Pattern
```funk
type Option = | Some { value: Int }, None |

let getOrDefault = {option: Option, default: Int} -> match option is
  Some { value } -> value
  None -> default
```

### State Machine Pattern
```funk
type State = | Idle, Loading, Success { data: String }, Error { message: String } |

let render = {state: State} -> match state is
  Idle -> "Ready to start"
  Loading -> "Please wait..."
  Success { data } -> "Data: " ++ data
  Error { message } -> "Error: " ++ message
```

### Validation Pattern
```funk
type Validation = | Valid { value: String }, Invalid { errors: String } |

let process = {validation: Validation} -> match validation is
  Valid { value } -> "Processing: " ++ value
  Invalid { errors } -> "Validation failed: " ++ errors
```

Pattern matching makes your code more readable, safer, and more expressive. Master these patterns and you'll write better Funk code! 🎯
