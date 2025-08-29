// Sample Graal.js script to test JavaScript execution and Java interop

// 1. Basic JavaScript functionality
console.log("Hello from Graal.js!");

// 2. Define a function
function add(a, b) {
    return a + b;
}

console.log("Sum of 3 and 4 is: " + add(3, 4));

// 3. Access Java classes from JavaScript (GraalVM polyglot feature)
var LocalDate = Java.type('java.time.LocalDate');
var today = LocalDate.now();

console.log("Today's date is: " + today);

// 4. Work with a Java ArrayList
var ArrayList = Java.type('java.util.ArrayList');
var list = new ArrayList();

list.add("Item 1");
list.add("Item 2");
list.add("Item 3");

console.log("Java ArrayList content: " + list);

// 5. Test interoperability with JavaScript and Java objects
list.forEach(function(item) {
    console.log("Processing: " + item);
});

// 6. Use a Java Lambda function from JavaScript
var Runnable = Java.type('java.lang.Runnable');
var runnable = new Runnable(function() {
    console.log("This is a Java Runnable running from Graal.js!");
});

runnable.run();
