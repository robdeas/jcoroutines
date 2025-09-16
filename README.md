# JCoroutines

**Structured concurrency for the JVM without the magic**

A Java-first coroutine library bringing structured concurrency, cooperative cancellation, and message-passing channels to any JVM project. No compiler plugins, no runtime bytecode manipulation - just clean APIs built on Virtual Threads and explicit context passing.

## Why JCoroutines?

- **Structured Concurrency**: Parent-child relationships ensure no leaked tasks
- **Cooperative Cancellation**: Explicit cancellation checks, not thread interruption
- **Message Passing**: Type-safe channels with backpressure handling
- **No Magic**: Everything is explicit - contexts are passed as parameters

## Requirements

- Java 21+ (uses Virtual Threads)
- No additional runtime dependencies

## Installation

```gradle
dependencies {
    implementation 'tech.robd.jcoroutines:jcoroutines-core:1.0.0'
}
```

## Quick Start

### Basic Usage

```java
import tech.robd.jcoroutines.*;

// Simple async operation
JCoroutineHandle<String> handle = Coroutines.async(suspend -> {
    suspend.delay(100);
    return "Hello, Coroutines!";
});

String result = handle.join();
System.out.println(result);
```

### Structured Concurrency

```java
try (var scope = new StandardCoroutineScope()) {
    // Launch multiple operations
    var handle1 = scope.async(suspend -> fetchData(suspend, "url1"));
    var handle2 = scope.async(suspend -> fetchData(suspend, "url2"));
    
    // Wait for results
    String result1 = handle1.join();
    String result2 = handle2.join();
} // Automatic cleanup and cancellation
```

### Channel Communication

```java
try (var scope = new StandardCoroutineScope()) {
    var channel = InteropChannel.<String>buffered(10);
    
    // Producer
    scope.launch(suspend -> {
        for (int i = 0; i < 5; i++) {
            channel.send(suspend, "Message " + i);
            suspend.yieldAndPause(Duration.ofMillis(100));
        }
        channel.close();
    });
    
    // Consumer
    scope.launch(suspend -> {
        channel.forEach(suspend, (ctx, msg) -> {
            System.out.println("Received: " + msg.orElse("null"));
        });
    });
}
```

## Key Concepts

### Suspend Functions
A "suspend function" takes `SuspendContext` as its first parameter:

```java
public String fetchData(SuspendContext suspend, String url) {
    suspend.checkCancellation(); // Check if cancelled
    suspend.yieldAndPause(Duration.ofMillis(10)); // Be cooperative
    return httpClient.get(url);
}
```

### Cooperative Scheduling
JCoroutines requires **explicit cooperation**:

- `suspend.yield()` - Basic yielding to other coroutines
- `suspend.yieldAndPause(Duration.ofMillis(10))` - **Recommended for cooperative tasks**
- `suspend.delay(Duration.ofSeconds(1))` - Actual time delays

### Cancellation
All operations check cancellation explicitly:

```java
public void longTask(SuspendContext suspend) {
    for (int i = 0; i < 1000; i++) {
        suspend.checkCancellation(); // Throws CancellationException if cancelled
        doWork(i);
        
        if (i % 100 == 0) {
            suspend.yieldAndPause(Duration.ofMillis(5)); // Be cooperative
        }
    }
}
```

## Spring Integration

```java
@Service
public class DataService {
    
    public CompletableFuture<List<String>> processAsync(List<String> items) {
        return Coroutines.asyncAndForget(suspend -> {
            try (var scope = new StandardCoroutineScope()) {
                var handles = items.stream()
                    .map(item -> scope.async(ctx -> process(ctx, item)))
                    .toList();
                    
                return handles.stream()
                    .map(JCoroutineHandle::join)
                    .toList();
            }
        });
    }
}
```

## Shutdown

```java
// Clean shutdown of global scopes
Runtime.getRuntime().addShutdownHook(new Thread(Coroutines::shutdown));
```

## Examples

## Next Steps

- Read the [Examples](\basic_examples.md) for getting started examples


## License

Apache 2.0