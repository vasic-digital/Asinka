# Asinka (Асинка)

Android IPC library based on gRPC for real-time object synchronization between applications.

## Features

- **Auto-discovery**: Real-time service discovery using Network Service Discovery (NSD)
- **Handshake Protocol**: Secure capability negotiation and permission exchange
- **Bi-directional Sync**: Real-time object property synchronization across all connected apps
- **Event System**: FCM-style event broadcasting with data payloads and priority levels
- **Encrypted Transport**: TLS-based encryption for all data transmission
- **Shared Database**: Optional Room Database with SQLCipher encryption
- **Kotlin Coroutines**: Full coroutine support with Flow-based reactive APIs

## Requirements

- Android 8.0 (API 26) or higher
- Target: Android 16 (API 36)
- Kotlin 2.1.0+

## Installation

### Gradle

```kotlin
dependencies {
    implementation("digital.vasic.asinka:asinka:0.1.0")
}
```

### Maven

```xml
<dependency>
    <groupId>digital.vasic.asinka</groupId>
    <artifactId>asinka</artifactId>
    <version>0.1.0</version>
</dependency>
```

## Quick Start

### 1. Initialize Asinka Client

```kotlin
val config = AsinkaConfig(
    appId = "com.example.myapp",
    appName = "My App",
    appVersion = "1.0.0",
    deviceId = "device-123",
    serverPort = 8888
)

val asinka = AsinkaClient.create(context, config)
asinka.start()
```

### 2. Define Syncable Objects

```kotlin
val schema = ObjectSchema(
    objectType = "Task",
    version = "1.0",
    fields = listOf(
        FieldSchema("title", FieldType.STRING),
        FieldSchema("completed", FieldType.BOOLEAN)
    ),
    permissions = listOf("read", "write")
)

val task = SyncableObjectData(
    objectId = "task-1",
    objectType = "Task",
    version = 1,
    fields = mutableMapOf(
        "title" to "Buy groceries",
        "completed" to false
    )
)

asinka.syncManager.registerObject(task)
```

### 3. Observe Changes

```kotlin
asinka.syncManager.observeObject("task-1")
    .collect { updatedObject ->
        println("Object updated: $updatedObject")
    }
```

### 4. Send Events

```kotlin
val event = AsinkaEvent(
    eventType = "notification",
    data = mapOf("message" to "Hello from app 1!"),
    priority = EventPriority.HIGH
)

asinka.eventManager.sendEvent(event)
```

### 5. Receive Events

```kotlin
class MyEventReceiver : AsinkaEventReceiver() {
    override val eventTypes = listOf("notification")

    override suspend fun handleEvent(event: AsinkaEvent) {
        val message = event.data["message"] as? String
        println("Received notification: $message")
    }
}

val receiver = MyEventReceiver()
asinka.eventManager.registerEventReceiver(receiver)
```

### 6. Discover and Connect

```kotlin
asinka.discoveryManager.startDiscovery()
    .collect { event ->
        when (event) {
            is DiscoveryEvent.ServiceFound -> {
                println("Found service: ${event.serviceInfo.serviceName}")
                asinka.connect(event.serviceInfo.host, event.serviceInfo.port)
            }
            is DiscoveryEvent.ServiceLost -> {
                println("Service lost: ${event.serviceName}")
            }
            is DiscoveryEvent.Error -> {
                println("Discovery error: ${event.message}")
            }
        }
    }
```

## Architecture

```
┌─────────────────────────────────────────┐
│          AsinkaClient (API)             │
├─────────────────────────────────────────┤
│  Discovery  │ Handshake │ Event System  │
├─────────────────────────────────────────┤
│   Transport (gRPC)  │  Sync Manager     │
├─────────────────────────────────────────┤
│   Security Manager  │  Storage (Room)   │
└─────────────────────────────────────────┘
```

## Testing

Asinka includes **101 comprehensive tests** providing **100% coverage** of core functionality:

- **89 Unit Tests**: SecurityManager, Discovery, Handshake, Sync, Events, Transport, Client API
- **12 Instrumentation Tests**: Full integration testing on real Android devices

```bash
# Run unit tests
./gradlew test

# Run instrumentation tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Run all tests with report generation
./scripts/run_all_tests.sh
```

Test reports are generated in `Tests/` directory with detailed logs and coverage metrics.

## Publishing

### Local Maven

```bash
./gradlew publishToMavenLocal
```

### Maven Central

Configure `.env` file with credentials:

```
MAVEN_CENTRAL_USERNAME=your_username
MAVEN_CENTRAL_PASSWORD=your_password
SIGNING_KEY_ID=your_key_id
SIGNING_PASSWORD=your_password
SIGNING_SECRET_KEY_RING_FILE=/path/to/key.gpg
```

Then publish:

```bash
./gradlew publishReleasePublicationToMavenCentralRepository
```

## Demo Application

See `demo-app/` module for a complete demonstration of all features.

## License

```
Copyright 2025 Vasic Digital

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

## Contributing

Contributions are welcome! Please open an issue or submit a pull request.

## Support

For issues and questions, please visit: https://github.com/vasic-digital/asinka/issues