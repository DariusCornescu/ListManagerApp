# ListManager - Smart Inventory Management App

## Overview

**ListManager** is a modern, offline-first mobile application designed to streamline inventory management for store owners and warehouse employees. The app enables hands-free product tracking through voice recognition, automatic catalog organization, and seamless multi-device synchronization.

## Key Features

### Voice-Driven Workflow

- **Hands-free product entry** using voice commands
- **Offline speech recognition** - works without internet connection
- **Smart product matching** with fuzzy search and alias support
- **Confidence-based suggestions** - automatically adds high-confidence matches or shows suggestions for review

### Intelligent Product Management

- **Organized catalog** with products grouped by distributor
- **Full-text search** with alias matching for flexible product discovery
- **Unknown products tracking** - captures unrecognized voice inputs for later cataloging
- **Product aliases** - multiple names for the same product (e.g., "Coca-Cola", "Coke", "Cola")

### Session Management

- **Active shopping sessions** - group products being ordered together
- **Real-time quantity adjustments** with intuitive plus/minus controls
- **Multi-distributor support** - automatically organizes orders by supplier
- **Session persistence** - all data saved locally and synced across devices

### Automated PDF Generation

- **Distributor-specific reports** - generates separate PDFs for each supplier
- **Professional formatting** with product lists and quantities
- **Offline generation** - creates PDFs without internet connection
- **Easy sharing** - export and share order lists via email or messaging

### Offline-First Architecture

- **100% offline functionality** - all features work without internet
- **Automatic sync** - changes upload to server when connection is restored
- **Conflict resolution** - smart handling of concurrent edits
- **Pending operations queue** - tracks offline changes for later synchronization

### Real-Time Collaboration

- **WebSocket integration** - instant updates across all connected devices
- **Multi-user support** - multiple employees can work simultaneously
- **Live session updates** - see changes from other users in real-time
- **User authentication** - secure login with JWT tokens

### Robust Data Persistence

- **Local SQLite database** (Room) - fast, reliable local storage
- **Server synchronization** - PostgreSQL backend for multi-device access
- **Data integrity** - cascade deletions and referential integrity
- **Conflict handling** - last-write-wins strategy with manual resolution options

## Technical Highlights

### Architecture

- **Native Android** (Kotlin) with Jetpack Compose UI
- **Flutter UI** alternative implementation
- **FastAPI backend** with PostgreSQL database
- **WebSocket** for real-time updates
- **Retrofit** for REST API communication

### Offline Capabilities

- **Room Database** for local persistence
- **Pending operations queue** for offline changes
- **Automatic retry** with exponential backoff
- **Network state detection** - adapts behavior based on connectivity

### Security

- **JWT authentication** - secure token-based auth
- **Bcrypt password hashing** - industry-standard encryption
- **Encrypted shared preferences** - secure local credential storage
- **Role-based access control** - admin and user roles

## Use Cases

### Warehouse Inventory

Store employees walk through aisles, using voice commands to identify missing products. The app automatically groups items by distributor and generates order lists.

### Multi-Location Stores

Different store locations collaborate on shared orders, with real-time updates visible across all devices.

### Offline Operations

Work continues seamlessly in areas with poor connectivity. All changes sync automatically when internet is restored.

### Product Cataloging

Unrecognized voice inputs are saved for review, allowing employees to add new products with aliases for better future recognition.

## Target Audience

- **Store owners** managing inventory across multiple locations
- **Warehouse employees** conducting stock checks
- **Purchasing managers** coordinating orders with suppliers
- **Small business owners** needing efficient inventory tracking

## Platform Support

- **Android Native** (Kotlin + Jetpack Compose)
- **Flutter** (cross-platform alternative)
- **Backend API** (FastAPI + PostgreSQL)

---

*ListManager - Making inventory management as simple as speaking.*
