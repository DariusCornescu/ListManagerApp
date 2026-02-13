```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              ANDROID APP                                      │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │   UI Layer  │  │  ViewModel  │  │ Repository  │  │  Local DB   │         │
│  │  (Compose)  │◄─┤   Layer     │◄─┤   Layer     │◄─┤   (Room)    │         │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘         │
│         │                │                │                │                  │
│         │                │                │                ▼                  │
│         │                │                │         ┌─────────────┐          │
│         │                │                │         │  Pending    │          │
│         │                │                │         │ Operations  │          │
│         │                │                │         └─────────────┘          │
│         │                │                │                │                  │
│         │                ▼                ▼                ▼                  │
│         │         ┌─────────────────────────────────────────┐               │
│         │         │           SYNC SERVICE                   │               │
│         │         │  ┌───────────┐  ┌───────────────────┐   │               │
│         │         │  │ SyncWorker│  │ WebSocketService  │   │               │
│         │         │  │(WorkMgr)  │  │  (Real-time)      │   │               │
│         │         │  └───────────┘  └───────────────────┘   │               │
│         │         └─────────────────────────────────────────┘               │
└─────────┼───────────────────────────┼───────────────────────────────────────┘
          │                           │
          │         HTTP/REST         │  WebSocket
          │                           │
          ▼                           ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           BACKEND (FastAPI)                                  │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │   Routers   │  │    CRUD     │  │   Models    │  │  Database   │         │
│  │  (Endpoints)│──┤  Operations │──┤  (SQLAlch.) │──┤  (SQLite)   │         │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘         │
│         │                                                                    │
│         ▼                                                                    │
│  ┌─────────────────────────────────┐                                        │
│  │  WebSocket Connection Manager   │                                        │
│  │  (Real-time broadcast)          │                                        │
│  └─────────────────────────────────┘                                        │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Tehnologii Folosite

### **Android (Frontend)**

| Tehnologie | Versiune | Scop |
|------------|----------|------|
| **Kotlin** | 1.9.x | Limbaj principal |
| **Jetpack Compose** | BOM 2024.x | UI declarativ modern |
| **Room Database** | 2.6.x | Persistență locală SQLite |
| **Retrofit** | 2.9.x | HTTP client pentru REST API |
| **OkHttp** | 4.12.x | HTTP client + WebSocket |
| **Kotlin Coroutines** | 1.7.x | Programare asincronă |
| **Kotlin Flow** | 1.7.x | Reactive streams |
| **WorkManager** | 2.9.x | Background sync tasks |
| **Navigation Compose** | 2.7.x | Navigare între ecrane |
| **Material 3** | 1.2.x | Design system |
| **SpeechRecognizer** | Android SDK | Voice input |

### **Backend (Server)**

| Tehnologie | Versiune | Scop |
|------------|----------|------|
| **Python** | 3.11+ | Limbaj principal |
| **FastAPI** | 0.104.x | Framework web async |
| **SQLAlchemy** | 2.0.x | ORM pentru database |
| **SQLite** | 3.x | Database (development) |
| **Pydantic** | 2.x | Data validation & serialization |
| **Uvicorn** | 0.24.x | ASGI server |
| **WebSockets** | native | Real-time communication |
| **JWT (PyJWT)** | 2.x | Authentication tokens |
| **Passlib + bcrypt** | - | Password hashing |

---

## Flow-uri Principale

### **1. 🔄 Offline-First Data Flow**

```
┌──────────────────────────────────────────────────────────────────┐
│                    OFFLINE-FIRST PATTERN                          │
└──────────────────────────────────────────────────────────────────┘

User Action (Add Product)
         │
         ▼
┌─────────────────┐
│  ViewModel      │ ──── Validare input
│  (addProduct)   │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Repository     │ ──── Business logic
│  (createProduct)│
└────────┬────────┘
         │
         ├────────────────────────────────┐
         │                                │
         ▼                                ▼
┌─────────────────┐              ┌─────────────────┐
│  Room Database  │              │ PendingOperation│
│  (Local Save)   │              │ (Queue for sync)│
│  ✅ INSTANT     │              │ status=PENDING  │
└─────────────────┘              └────────┬────────┘
                                          │
                    ┌─────────────────────┘
                    │
                    ▼
         ┌──────────────────┐
         │  Network Check   │
         │  (isOnline?)     │
         └────────┬─────────┘
                  │
        ┌─────────┴─────────┐
        │                   │
        ▼                   ▼
   [ONLINE]            [OFFLINE]
        │                   │
        ▼                   ▼
┌───────────────┐   ┌───────────────┐
│ API Call      │   │ Stay in Queue │
│ POST /products│   │ Wait for      │
└───────┬───────┘   │ connectivity  │
        │           └───────────────┘
        ▼
┌───────────────┐
│ Update Local  │
│ with server ID│
│ Mark SYNCED   │
└───────────────┘
```

### **2. WebSocket Real-Time Sync**

```
┌──────────────────────────────────────────────────────────────────┐
│                    WEBSOCKET FLOW                                 │
└──────────────────────────────────────────────────────────────────┘

┌─────────────┐                              ┌─────────────┐
│  Android    │                              │   Server    │
│   Client    │                              │  (FastAPI)  │
└──────┬──────┘                              └──────┬──────┘
       │                                            │
       │  ──── ws://192.168.x.x:8000/ws/client1 ───►│
       │           CONNECT                          │
       │                                            │
       │  ◄──────── CONNECTION_ACK ────────────────│
       │                                            │
       │                                            │
       │  ──────── PING (keep-alive) ──────────────►│
       │  ◄─────── PONG ───────────────────────────│
       │                                            │
       │                                            │
       │           [Another client adds product]    │
       │                                            │
       │  ◄──── BROADCAST: catalog_update ─────────│
       │        { type: "product_created",          │
       │          data: { id: 5, name: "..." } }    │
       │                                            │
       │  [App updates local DB & UI automatically] │
       │                                            │
```

### **3. Session Management Flow**

```
┌──────────────────────────────────────────────────────────────────┐
│                    SESSION FLOW                                   │
└──────────────────────────────────────────────────────────────────┘

                    ┌─────────────────┐
                    │   HOME SCREEN   │
                    │  (Active/Past   │
                    │   Sessions)     │
                    └────────┬────────┘
                             │
              ┌──────────────┼──────────────┐
              │              │              │
              ▼              ▼              ▼
       [New Session]  [Resume Session] [View History]
              │              │              │
              ▼              ▼              │
       ┌─────────────────────────┐         │
       │    SESSION SCREEN       │         │
       │  ┌───────────────────┐  │         │
       │  │  🎤 Voice Input   │  │         │
       │  │  "Pepsi 2 litri"  │  │         │
       │  └─────────┬─────────┘  │         │
       │            │            │         │
       │            ▼            │         │
       │  ┌───────────────────┐  │         │
       │  │ Product Matcher   │  │         │
       │  │ (Fuzzy Search)    │  │         │
       │  └─────────┬─────────┘  │         │
       │            │            │         │
       │     ┌──────┴──────┐     │         │
       │     │             │     │         │
       │     ▼             ▼     │         │
       │ [MATCH]      [NO MATCH] │         │
       │     │             │     │         │
       │     ▼             ▼     │         │
       │ Add to       Add to     │         │
       │ Session      "Unknown"  │         │
       │ Items        Products   │         │
       │                         │         │
       └─────────────────────────┘         │
                                           │
                                           ▼
                                  ┌─────────────────┐
                                  │ SESSION HISTORY │
                                  │ (Past sessions  │
                                  │  with items)    │
                                  └─────────────────┘
```

### **4. Voice Recognition Flow**

```
┌──────────────────────────────────────────────────────────────────┐
│                    VOICE INPUT FLOW                               │
└──────────────────────────────────────────────────────────────────┘

User taps 🎤 button
         │
         ▼
┌─────────────────────┐
│ SpeechRecognition   │
│ Helper              │
│ - Creates Recognizer│
│ - Sets Romanian     │
│   language (ro-RO)  │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│ Android Speech API  │
│ - Records audio     │
│ - Sends to Google   │
│   Speech Services   │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│ onResults callback  │
│ "pepsi doi litri"   │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│ Text Processing     │
│ - Parse quantity    │
│ - Extract product   │
│   name              │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│ Product Matching    │
│ - Search in catalog │
│ - Fuzzy matching    │
│ - Check aliases     │
└──────────┬──────────┘
           │
     ┌─────┴─────┐
     │           │
     ▼           ▼
[FOUND]     [NOT FOUND]
     │           │
     ▼           ▼
Add to      Add to
Session     Unknown
Item        Products
```

---

## Database Schema

### **Android (Room)**

```sql
-- Distribuitori
CREATE TABLE distributors (
    id INTEGER PRIMARY KEY,
    distributor_name TEXT NOT NULL
);

-- Produse
CREATE TABLE products (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    distributor_id INTEGER,
    aliases TEXT,  -- JSON array sau comma-separated
    FOREIGN KEY (distributor_id) REFERENCES distributors(id)
);

-- Sesiuni
CREATE TABLE sessions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    server_id INTEGER,  -- ID din server (null dacă nu e sincronizat)
    name TEXT NOT NULL,
    is_active INTEGER DEFAULT 1,
    created_at INTEGER NOT NULL,
    sync_status TEXT DEFAULT 'PENDING'
);

-- Items din sesiune
CREATE TABLE session_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    server_id INTEGER,
    session_id INTEGER NOT NULL,
    product_id INTEGER,
    product_name TEXT NOT NULL,
    quantity INTEGER DEFAULT 1,
    created_at INTEGER NOT NULL,
    FOREIGN KEY (session_id) REFERENCES sessions(id),
    FOREIGN KEY (product_id) REFERENCES products(id)
);

-- Operații în așteptare (pentru offline sync)
CREATE TABLE pending_operations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    operation_type TEXT NOT NULL,  -- CREATE, UPDATE, DELETE
    entity_type TEXT NOT NULL,     -- PRODUCT, SESSION, etc.
    entity_id INTEGER,
    payload TEXT,                  -- JSON cu datele
    status TEXT DEFAULT 'PENDING', -- PENDING, SYNCING, FAILED
    retry_count INTEGER DEFAULT 0,
    created_at INTEGER NOT NULL,
    last_attempted_at INTEGER
);

-- Produse necunoscute
CREATE TABLE unknown_products (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    session_id INTEGER,
    occurrences INTEGER DEFAULT 1,
    created_at INTEGER NOT NULL
);
```

### **Server (SQLAlchemy)**

```python
# models.py

class User(Base):
    __tablename__ = "users"
    id = Column(Integer, primary_key=True)
    username = Column(String, unique=True, index=True)
    email = Column(String, unique=True)
    hashed_password = Column(String)
    is_active = Column(Boolean, default=True)
    created_at = Column(DateTime, default=datetime.utcnow)

class Distributor(Base):
    __tablename__ = "distributors"
    id = Column(Integer, primary_key=True)
    name = Column(String, index=True)
    contact_info = Column(String, nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow)
    products = relationship("Product", back_populates="distributor")

class Product(Base):
    __tablename__ = "products"
    id = Column(Integer, primary_key=True)
    name = Column(String, index=True)
    distributor_id = Column(Integer, ForeignKey("distributors.id"))
    aliases = Column(String, nullable=True)  # Comma-separated aliases
    created_at = Column(DateTime, default=datetime.utcnow)
    distributor = relationship("Distributor", back_populates="products")

class Session(Base):
    __tablename__ = "sessions"
    id = Column(Integer, primary_key=True)
    name = Column(String)
    is_active = Column(Boolean, default=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow)
    items = relationship("SessionItem", back_populates="session")

class SessionItem(Base):
    __tablename__ = "session_items"
    id = Column(Integer, primary_key=True)
    session_id = Column(Integer, ForeignKey("sessions.id"))
    product_id = Column(Integer, ForeignKey("products.id"), nullable=True)
    product_name = Column(String)
    quantity = Column(Integer, default=1)
    version = Column(Integer, default=1)  # For conflict resolution
    session = relationship("Session", back_populates="items")
```

---

## Sync & Conflict Resolution

### **Sync States**

```kotlin
enum class SyncStatus {
    PENDING,    // Așteaptă sincronizare
    SYNCING,    // În curs de sincronizare
    SYNCED,     // Sincronizat cu serverul
    FAILED,     // Eroare la sincronizare
    CONFLICT    // Conflict detectat
}
```

### **Conflict Resolution Strategy**

```
┌──────────────────────────────────────────────────────────────────┐
│                    CONFLICT RESOLUTION                            │
└──────────────────────────────────────────────────────────────────┘

        Local Change                    Server Change
              │                               │
              ▼                               ▼
       ┌─────────────┐                ┌─────────────┐
       │ version: 2  │                │ version: 3  │
       │ qty: 5      │                │ qty: 10     │
       └──────┬──────┘                └──────┬──────┘
              │                               │
              └───────────┬───────────────────┘
                          │
                          ▼
                ┌───────────────────┐
                │  Version Compare  │
                │  local < server   │
                └─────────┬─────────┘
                          │
                          ▼
                ┌───────────────────┐
                │  SERVER WINS      │
                │  (Last-Write-Wins)│
                │  Update local to  │
                │  version: 3, qty:10│
                └───────────────────┘
```

---

##  API Endpoints Summary

| Method | Endpoint | Descriere | Auth |
|--------|----------|-----------|------|
| `GET` | `/` | Health check | ❌ |
| `GET` | `/docs` | Swagger UI | ❌ |
| **Distributors** |
| `GET` | `/distributors/` | List all | ❌ |
| `POST` | `/distributors/` | Create | ✅ |
| `GET` | `/distributors/{id}` | Get one | ❌ |
| `PUT` | `/distributors/{id}` | Update | ✅ |
| `DELETE` | `/distributors/{id}` | Delete | ✅ |
| **Products** |
| `GET` | `/products/` | List all | ❌ |
| `POST` | `/products/` | Create | ✅ |
| `GET` | `/products/{id}` | Get one | ❌ |
| `PUT` | `/products/{id}` | Update | ✅ |
| `DELETE` | `/products/{id}` | Delete | ✅ |
| **Sessions** |
| `GET` | `/sessions/` | List all | ❌ |
| `POST` | `/sessions/` | Create | ❌ |
| `GET` | `/sessions/{id}` | Get with items | ❌ |
| `POST` | `/sessions/{id}/items` | Add item | ❌ |
| `PUT` | `/sessions/{id}/items/{item_id}` | Update item | ❌ |
| `DELETE` | `/sessions/{id}/items/{item_id}` | Delete item | ❌ |
| **Auth** |
| `POST` | `/auth/register` | Register | ❌ |
| `POST` | `/auth/login` | Login | ❌ |
| `GET` | `/auth/me` | Current user | ✅ |
| **WebSocket** |
| `WS` | `/ws/{client_id}` | Real-time sync | ❌ |

---

## Cum să pornești aplicația

### **Backend**

```bash
cd backend-fastapi
pip install -r requirements.txt
python -m app.seed          # Seed database cu 48 distribuitori
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

### **Android**

```bash
cd android-native
# Editează ApiConfig.kt cu IP-ul tău local
./gradlew installDebug
```

### **Verificare**

- API Docs: `http://localhost:8000/docs`
- Distribuitori: `http://localhost:8000/distributors/`
- WebSocket test: `ws://localhost:8000/ws/test-client`