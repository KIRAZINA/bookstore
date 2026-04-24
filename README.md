# Bookstore API

REST API for an online bookstore built with Spring Boot 4, JWT authentication, H2, Swagger/OpenAPI, and a standalone HTML API tester.

## Current Status
- Spring Boot 4 / Java 21 project
- JWT auth is working
- Swagger UI is integrated
- OpenAPI docs are exposed
- Main API flows were verified locally on `http://localhost:8080`
- Maven test suite is passing

## Tech Stack
- Java 21
- Spring Boot 4.0.0
- Spring Security
- Spring Data JPA
- H2 in-memory database
- springdoc-openapi / Swagger UI
- Maven Wrapper

## Features
- User registration and login with JWT
- Public book catalog endpoints
- Admin-only book management
- User cart management
- Order creation from the active cart
- Optimistic locking support for stock updates
- Interactive Swagger UI and OpenAPI docs
- Standalone `api-tester.html` for browser-based API testing

## Repository Layout
```text
src/main/java/com/example/bookstore/
|-- config/        Security, JWT, exception handling, OpenAPI config
|-- controller/    REST controllers and DTOs
|-- model/         JPA entities and enums
|-- repository/    Spring Data repositories
`-- service/       Business logic
```

## Run Locally

Requirements:
- Java 21+

Start the app on Windows:

```bash
mvnw.cmd spring-boot:run
```

Start the app on macOS/Linux:

```bash
./mvnw spring-boot:run
```

The application starts at `http://localhost:8080`.

Run tests:

```bash
mvnw.cmd test
```

## API Documentation
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- OpenAPI YAML: `http://localhost:8080/v3/api-docs.yaml`
- H2 Console: `http://localhost:8080/h2-console`
- Standalone local tester: [api-tester.html](api-tester.html)

## Default Seed Data

Users:
- Admin: `admin / admin123`
- User: `testuser / testuser`

Books:
- `Book1` by `Author1` (`Fiction`, stock `10`)
- `Book2` by `Author2` (`SciFi`, stock `5`)

## Authentication

Protected endpoints require:

```text
Authorization: Bearer <jwt-token>
```

Get the token from `POST /api/auth/login`.

## API Endpoints

### Auth
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| POST | `/api/auth/register` | Public | Register a new user |
| POST | `/api/auth/login` | Public | Login and receive JWT |
| GET | `/api/auth/me` | Authenticated | Get current user profile |

### Books
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| GET | `/api/books` | Public | List all books |
| GET | `/api/books/category?category=Fiction` | Public | Filter books by category |
| GET | `/api/books/search?search=Book` | Public | Search books by title or author |
| POST | `/api/books` | Admin | Create a new book |
| PUT | `/api/books/{id}/stock` | Admin | Update book stock |
| DELETE | `/api/books/{id}` | Admin | Delete a book |

### Cart
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| GET | `/api/cart` | User | Get current cart |
| POST | `/api/cart/items` | User | Add item to cart |
| PUT | `/api/cart/items/{bookId}` | User | Update cart item quantity |
| DELETE | `/api/cart/items/{bookId}` | User | Remove item from cart |
| DELETE | `/api/cart` | User | Clear cart |

### Orders
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| POST | `/api/orders` | User | Create order from cart |
| GET | `/api/orders?page=0&size=10&sortBy=createdAt&direction=desc` | User | Get paged order history |

## Example Requests

Register:

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"newuser\",\"password\":\"password123\",\"email\":\"newuser@example.com\"}"
```

Login:

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"testuser\",\"password\":\"testuser\"}"
```

Create a book as admin:

```bash
curl -X POST http://localhost:8080/api/books \
  -H "Authorization: Bearer <admin-token>" \
  -H "Content-Type: application/json" \
  -d "{\"title\":\"Clean Code\",\"author\":\"Robert C. Martin\",\"price\":39.99,\"category\":\"Programming\",\"stock\":8}"
```

Add to cart:

```bash
curl -X POST http://localhost:8080/api/cart/items \
  -H "Authorization: Bearer <user-token>" \
  -H "Content-Type: application/json" \
  -d "{\"bookId\":1,\"quantity\":2}"
```

Create order:

```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Authorization: Bearer <user-token>"
```

## GitHub Notes
- The repository is configured to keep Maven Wrapper files tracked.
- Build output, IDE files, logs, and local environment files are ignored in `.gitignore`.
- Swagger/OpenAPI is enabled for local development and documentation.
- The in-memory H2 database resets on restart, so demo data is reproducible.

## Notes
- Book write operations require `ROLE_ADMIN`.
- Cart and order endpoints require `ROLE_USER`.
- Validation errors return `400 Bad Request`.
- Swagger endpoints are intentionally open for local testing.
