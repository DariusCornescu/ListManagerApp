# API Tests

Comprehensive test suite for the List Manager API.

## Test Structure

```
tests/
├── conftest.py          # Test configuration and fixtures
├── test_auth.py         # Authentication & authorization tests
├── test_catalog.py      # Product & distributor CRUD tests
├── test_sessions.py     # Session management & items tests
└── README.md           # This file
```

## Test Coverage

### Authentication Tests (`test_auth.py`)
- **Root & Health Endpoints**
  - Root endpoint
  - Health check

- **User Registration**
  - Successful registration
  - Duplicate username rejection
  - Duplicate email rejection

- **User Login**
  - Successful login with valid credentials
  - Login failure with wrong password
  - Login failure with nonexistent user
  - Login failure for inactive users

- **Authenticated Endpoints**
  - Access with valid token
  - Access denied without token
  - Protected endpoint access

- **Admin Endpoints**
  - Admin can access admin-only endpoints
  - Regular users cannot access admin endpoints
  - Admin can manage users
  - Admin cannot delete themselves

### Catalog Tests (`test_catalog.py`)
- **Distributor CRUD**
  - Create new distributor
  - Prevent duplicate distributor names
  - List all distributors
  - Get distributor by ID
  - Update distributor
  - Delete distributor
  - 404 errors for nonexistent distributors

- **Product CRUD**
  - Create new product
  - List all products
  - Search products by name
  - Search products by alias
  - Get product by ID
  - Update product
  - Delete product
  - 404 errors for nonexistent products

### Session Tests (`test_sessions.py`)
- **Session Management**
  - Get active session
  - Create new session
  - New session deactivates previous session
  - Complete session
  - Group items by distributor on completion
  - Prevent completing empty sessions

- **Session Items**
  - Get all items in a session
  - Add new item to session
  - Increment quantity for existing items
  - Update item with optimistic locking
  - Version conflict detection (409)
  - Delete individual items
  - Clear all items from session

- **Statistics**
  - Get application statistics
  - Active session information
  - Statistics when no active session

## Running Tests

### Run all tests
```bash
pytest
```

### Run specific test file
```bash
pytest tests/test_auth.py
pytest tests/test_catalog.py
pytest tests/test_sessions.py
```

### Run specific test class
```bash
pytest tests/test_auth.py::TestUserRegistration
pytest tests/test_catalog.py::TestProductCRUD
pytest tests/test_sessions.py::TestSessionItems
```

### Run specific test
```bash
pytest tests/test_auth.py::TestUserLogin::test_login_success
```

### Run with coverage
```bash
pytest --cov=app --cov-report=html
```

### Run with verbose output
```bash
pytest -v
```

### Run with print statements visible
```bash
pytest -s
```

## Test Database

Tests use an in-memory SQLite database that is created fresh for each test function. This ensures:
- Tests are isolated and don't affect each other
- Fast test execution
- No need for database cleanup
- No external dependencies

## Fixtures

Common fixtures available in `conftest.py`:

- `client` - TestClient instance for making API requests
- `db_session` - Database session for direct database operations
- `sample_user` - Regular user for authentication tests
- `sample_admin` - Admin user for authorization tests
- `user_token` - JWT token for regular user
- `admin_token` - JWT token for admin user
- `auth_headers` - Authorization headers for regular user
- `admin_headers` - Authorization headers for admin
- `sample_distributor` - Test distributor
- `sample_product` - Test product
- `sample_session` - Test global session
- `sample_session_item` - Test session item

## Test Patterns

### Testing Authentication
```python
def test_protected_endpoint(self, client, auth_headers):
    response = client.get("/api/protected/test", headers=auth_headers)
    assert response.status_code == 200
```

### Testing CRUD Operations
```python
def test_create_resource(self, client):
    data = {"name": "Test"}
    response = client.post("/api/resource", json=data)
    assert response.status_code == 200
    assert response.json()["name"] == "Test"
```

### Testing Error Cases
```python
def test_nonexistent_resource(self, client):
    response = client.get("/api/resource/99999")
    assert response.status_code == 404
    assert "not found" in response.json()["detail"]
```

### Testing Optimistic Locking
```python
def test_version_conflict(self, client, sample_item):
    update_data = {"quantity": 10, "version": 999}
    response = client.put(f"/api/items/{sample_item.id}", json=update_data)
    assert response.status_code == 409
```

## Continuous Integration

These tests are designed to run in CI/CD pipelines. They:
- Require no external services
- Use in-memory database
- Complete in seconds
- Have no side effects

## Adding New Tests

1. Create test in appropriate file or new file
2. Use descriptive test names: `test_<action>_<expected_result>`
3. Follow AAA pattern: Arrange, Act, Assert
4. Use fixtures for common setup
5. Test both success and error cases
6. Include docstrings explaining what is tested

## Test Count Summary

- **Authentication Tests**: 17 tests
- **Catalog Tests**: 27 tests
- **Session Tests**: 23 tests
- **Total**: 67+ comprehensive tests

All endpoints are covered with both success and error scenarios!
