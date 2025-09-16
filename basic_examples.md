# JCoroutines Basic Examples
[← Back to README](README.md)

This guide shows the fundamental patterns for using JCoroutines in your Java applications.

## Prerequisites

```gradle
dependencies {
    implementation 'tech.robd.jcoroutines:jcoroutines-core:1.0.0'
}
```

## Example 1: Simple Async Operation

The most basic usage - launch an async operation and get the result:

```java
import tech.robd.jcoroutines.*;

public class SimpleExample {
    public static void main(String[] args) {
        // Launch async operation using global scope
        JCoroutineHandle<String> handle = Coroutines.async(suspend -> {
            suspend.delay(100); // Simulate async work
            return "Async result";
        });
        
        // Get the result (blocks until complete)
        String result = handle.join();
        System.out.println("Result: " + result);
        
        // Clean shutdown
        Coroutines.shutdown();
    }
}
```

## Example 2: Structured Concurrency

Multiple operations with automatic cleanup:

```java
public class StructuredExample {
    public static void main(String[] args) {
        try (var scope = new StandardCoroutineScope()) {
            // Launch 3 concurrent operations
            var handle1 = scope.async(suspend -> fetchData(suspend, "API-1", 150));
            var handle2 = scope.async(suspend -> fetchData(suspend, "API-2", 100));
            var handle3 = scope.async(suspend -> fetchData(suspend, "API-3", 200));
            
            // Collect results
            System.out.println("Result 1: " + handle1.join());
            System.out.println("Result 2: " + handle2.join());
            System.out.println("Result 3: " + handle3.join());
        } // Scope automatically cancels remaining operations and cleans up
    }
    
    private static String fetchData(SuspendContext suspend, String source, long delayMs) {
        suspend.checkCancellation(); // Check if cancelled
        suspend.delay(delayMs);      // Simulate network delay
        return "Data from " + source;
    }
}
```

## Example 3: Producer-Consumer with Channels

Channel-based communication between coroutines:

```java
import java.time.Duration;
public class ChannelExample {
    public static void main(String[] args) throws InterruptedException {
        try (var scope = new StandardCoroutineScope()) {
            var channel = InteropChannel.<String>buffered(5);
            
            // Producer coroutine
            scope.launch(suspend -> {
                for (int i = 1; i <= 5; i++) {
                    String message = "Message-" + i;
                    System.out.println("Sending: " + message);
                    channel.send(suspend, message);
                    suspend.yieldAndPause(Duration.ofMillis(50)); // Be cooperative
                }
                channel.close();
                System.out.println("Producer finished");
            });
            
            // Consumer coroutine
            scope.launch(suspend -> {
                channel.forEach(suspend, (ctx, msg) -> {
                    System.out.println("Received: " + msg.orElse("null"));
                    ctx.yieldAndPause(Duration.ofMillis(30)); // Be cooperative
                });
                System.out.println("Consumer finished");
            });
            
            // Give operations time to complete
            Thread.sleep(500);
        }
    }
}
```

## Example 4: Cancellation

How to cancel operations and handle cancellation:

```java
public class CancellationExample {
    public static void main(String[] args) throws InterruptedException {
        try (var scope = new StandardCoroutineScope()) {
            var handle = scope.async(suspend -> {
                for (int i = 0; i < 10; i++) {
                    suspend.checkCancellation(); // Will throw if cancelled
                    System.out.println("Working... " + i);
                    suspend.delay(100);
                }
                return "Completed normally";
            });
            
            // Cancel after 300ms
            Thread.sleep(300);
            handle.cancel();
            
            try {
                String result = handle.join();
                System.out.println("Result: " + result);
            } catch (java.util.concurrent.CompletionException e) {
                if (e.getCause() instanceof java.util.concurrent.CancellationException) {
                    System.out.println("Operation was cancelled");
                } else { 
                    throw e; // rethrow unexpected failures
                }
            }
        }
    }
}
```

## Example 5: Cooperative Scheduling

Demonstrating proper yielding for cooperative multitasking:

```java
import java.time.Duration;
import java.util.stream.Collectors;
import java.util.List;
public class CooperativeExample {
    public static void main(String[] args) {
        List<String> results = Coroutines.runBlocking(suspend -> {
            try (var scope = new StandardCoroutineScope()) {
                // Launch background task that yields cooperatively
                scope.launch(backgroundTask -> {
                    for (int i = 0; i < 5; i++) {
                        System.out.println("Background task: " + i);
                        // Yield to allow other coroutines to run
                        backgroundTask.yieldAndPause(Duration.ofMillis(20));
                    }
                });
                
                // Main processing that also yields
                return List.of("item1", "item2", "item3").stream()
                    .map(item -> {
                        suspend.yieldAndPause(Duration.ofMillis(30)); // Be cooperative
                        return "processed-" + item;
                    })
                    .collect(Collectors.toList());
            }
        });
        
        System.out.println("Final results: " + results);
        Coroutines.shutdown();
    }
}
```

## Key Patterns

### 1. Suspend Function Pattern
Every suspend function takes `SuspendContext` as first parameter:

```java
public String myAsyncOperation(SuspendContext suspend, String input) {
    suspend.checkCancellation();           // Check for cancellation
    suspend.yieldAndPause(Duration.ofMillis(10)); // Be cooperative
    
    // Do your work here
    String result = processInput(input);
    
    suspend.delay(100);                    // Real delay if needed
    return result;
}
```

### 2. Resource Management Pattern
Always use try-with-resources for scopes:

```java
try (var scope = new StandardCoroutineScope()) {
    // Launch operations
    var handle = scope.async(suspend -> doWork(suspend));
    return handle.join();
} // Automatic cleanup
```

### 3. Cooperative Scheduling Pattern
Use the right method for the right purpose:

```java
// For cooperative yielding (recommended for most cases)
suspend.yieldAndPause(Duration.ofMillis(10));

// For basic yielding without timing
suspend.yield();

// For actual delays (timers, retries, etc.)
suspend.delay(Duration.ofSeconds(1));
```

### 4. Error Handling Pattern
Handle cancellation explicitly:

```java
try {
    String result = handle.join();
    // Process result
} catch (java.util.concurrent.CompletionException e) {
    if (e.getCause() instanceof java.util.concurrent.CancellationException) {
        System.out.println("Operation cancelled");
    } else {
        throw e; // rethrow unexpected failures
    }
}
```
