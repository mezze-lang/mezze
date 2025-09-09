// JavaScript side of polyglot demo
// This shows how to call Funk functions from JavaScript

// Load the Funk module
const funkModule = Polyglot.eval('funk', `
    let add = { x: Int, y: Int } -> x + y
    let greet = { person: { name: String, age: Int } } -> 
        "Hello " + person.name + ", you are " + person.age + " years old!"
    let createPerson = { name: String, age: Int } -> 
        { name = name, age = age }
    
    // Return functions for JS to use
    {
        add = add,
        greet = greet,
        createPerson = createPerson
    }
`);

// Call Funk functions from JavaScript
console.log("=== Funk ↔ JavaScript Interop Demo ===");

// Simple function call
const result = funkModule.add({ x: 5, y: 3 });
console.log("Funk add(5, 3) =", result);

// Create a person using Funk
const person = funkModule.createPerson({ name: "Alice", age: 30 });
console.log("Created person:", person);

// Use Funk to greet the person
const greeting = funkModule.greet({ person: person });
console.log("Funk greeting:", greeting);

// JavaScript can also create objects for Funk
const jsPerson = { name: "Bob", age: 25 };
const jsGreeting = funkModule.greet({ person: jsPerson });
console.log("JS person greeted by Funk:", jsGreeting);

// Show that Funk records are accessible from JS
console.log("Person name from JS:", person.name);
console.log("Person age from JS:", person.age);
