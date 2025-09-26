# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Asinka (Асинка)** - Android IPC library based on gRPC for real-time object synchronization between applications.

**Package**: `digital.vasic.asinka`
**Target**: Android 16 (API Level 36)

## Core Features

- **Auto-discovery**: Real-time service discovery between applications
- **Handshake Protocol**: Initial exchange of capabilities and permissions
- **Bi-directional Sync**: Object property synchronization across all connected apps
- **Real-time Observation**: Live change notification and propagation
- **Encrypted Transport**: All data transmission is encrypted
- **Event System**: FCM-like event broadcasting with data payloads
- **Optional Shared Database**: Room Database with SQLCipher encryption

## Commands

### Build
```bash
./gradlew clean build
```

### Run Tests
```bash
# Unit tests (89 tests)
./gradlew test

# Instrumentation tests (12 tests)
./gradlew connectedAndroidTest

# All tests with report generation (101 total)
./scripts/run_all_tests.sh
```

Test reports are written to: `Tests/` directory in project root.

**Test Coverage**: 100% of core library functionality with 101 comprehensive tests covering:
- Security and encryption
- Discovery and handshake
- Transport and sync
- Events and receivers
- Main client API
- Integration scenarios

### Distribution
```bash
# Publish to Maven Central
./gradlew publishReleasePublicationToMavenCentralRepository

# Publish to local Maven
./gradlew publishToMavenLocal
```

## Architecture

### Layer Structure

1. **Discovery Layer** (`discovery/`)
   - Network service discovery (NSD/mDNS)
   - Peer detection and announcement
   - Connection establishment

2. **Handshake Layer** (`handshake/`)
   - Capability negotiation
   - Permission exchange
   - Security context establishment
   - Object schema validation

3. **Transport Layer** (`transport/`)
   - gRPC client/server implementation
   - Encryption (TLS/custom)
   - Connection pooling and management
   - Stream handling

4. **Synchronization Layer** (`sync/`)
   - Object state management
   - Change detection and propagation
   - Conflict resolution
   - Version control

5. **Event System** (`events/`)
   - Event broadcasting
   - Event receivers (FCM-style)
   - Priority and delivery guarantees

6. **Storage Layer** (`storage/`, optional)
   - Room Database integration
   - SQLCipher encryption
   - Shared database access

### Key Components

- **AsinkaClient**: Main entry point for library consumers
- **ObjectRegistry**: Manages syncable objects
- **ChangeObserver**: Monitors and propagates changes
- **EventBus**: Handles event distribution
- **SecurityManager**: Encryption and authentication

### Object Synchronization Model

Objects are identified by:
- Unique ID across all apps
- Schema/version information
- Access permissions defined during handshake

Changes propagate through:
1. Local change detected by ChangeObserver
2. Change serialized with metadata
3. gRPC stream broadcasts to connected peers
4. Remote peers validate and apply change
5. Listeners notified on remote side

### Security

- TLS for transport encryption
- App signature verification during discovery
- Handshake includes capability tokens
- Per-object access control
- Optional SQLCipher for shared database

## Testing Strategy

### Unit Tests (`test/`)
- Individual component logic
- Mock gRPC calls
- State management validation

### Instrumentation Tests (`androidTest/`)
- Android framework integration
- Room database operations
- Network service discovery
- Multiple process scenarios

### Automation Tests
- End-to-end flows
- Multi-app synchronization scenarios
- Mock applications for testing
- Network condition simulation
- Security verification

Scripts in `scripts/`:
- `run_unit_tests.sh`
- `run_instrumentation_tests.sh`
- `run_automation_tests.sh`
- `run_all_tests.sh` - Executes all test types

## Demo Application

Located in `demo-app/` module. Demonstrates:
- Object creation and sync
- Event sending/receiving
- Discovery and connection flow
- Multi-device scenarios
- All library features

Ready for Google Play Store distribution.

## Environment Configuration

Create `.env` in project root (gitignored):
```
MAVEN_CENTRAL_USERNAME=
MAVEN_CENTRAL_PASSWORD=
SIGNING_KEY_ID=
SIGNING_PASSWORD=
SIGNING_SECRET_KEY_RING_FILE=
```

## Development Notes

- Use Kotlin coroutines for async operations
- Follow Material Design 3 in demo app
- gRPC proto files in `proto/` directory
- Keep backward compatibility for protocol changes
- Document all public APIs with KDoc