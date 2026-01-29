# Bookstore API

[![Build Status](https://github.com/KIRAZINA/bookstore/actions/workflows/maven.yml/badge.svg)](https://github.com/KIRAZINA/bookstore/actions/workflows/maven.yml) [![Java 17](https://img.shields.io/badge/Java-17-blue)](https://openjdk.java.net/projects/jdk/17/) [![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.x-green)](https://spring.io/projects/spring-boot)

Spring Boot application for an online bookstore API with JWT authentication.

## Features
- **Authentication**: JWT (login/register) with secure password encoding
- **CRUD**: Books with pagination and sorting (admin only for modifications)
- **Shopping Cart**: Add, update, remove items
- **Orders**: Create orders from cart, view order history
- **Database**: H2 in-memory (for dev), PostgreSQL ready (production)
- **Security**: Spring Security + JJWT, USER/ADMIN roles
- **API Documentation**: Swagger/OpenAPI UI
- **Testing**: 32 unit tests (models, services)

## Setup
- JDK 17+
- Maven
- Environment variable: `JWT_SECRET` (required for production)

## Technologies
- Java 17
- Spring Boot 3.x
- Spring Security + JJWT 0.12.x
- Hibernate/JPA
- Maven
- H2 Database
- SpringDoc OpenAPI
- Lombok

## Steps
1. Clone the repo:
   ```bash
   git clone https://github.com/KIRAZINA/bookstore.git
   cd bookstore
   ```
2. Set JWT_SECRET (optional for dev, required for production):
   ```bash
   export JWT_SECRET="your_256_bit_secret_key_here"
   ```
3. Build and run:
   ```bash
   mvnw spring-boot:run
   ```
4. Server at http://localhost:8080
5. Swagger UI: http://localhost:8080/swagger-ui.html
6. H2 Console: http://localhost:8080/h2-console (jdbc:h2:mem:testdb)

## API Endpoints

### Auth
| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| POST | `/api/auth/register` | Register new user | No |
| POST | `/api/auth/login` | Login, returns JWT | No |

### Books
| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | `/api/books` | List books (paginated) | No |
| GET | `/api/books?category=Fiction` | Filter by category | No |
| GET | `/api/books/search?search=java` | Search by title/author | No |
| POST | `/api/books` | Add new book | ADMIN |
| DELETE | `/api/books/{id}` | Delete book | ADMIN |
| PUT | `/api/books/{id}/stock?stock=10` | Update stock | ADMIN |

### Cart
| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | `/api/cart` | View cart | USER |
| POST | `/api/cart/add?bookId=1&quantity=2` | Add item | USER |
| PUT | `/api/cart/update?bookId=1&quantity=5` | Update quantity | USER |
| DELETE | `/api/cart/remove?bookId=1` | Remove item | USER |
| DELETE | `/api/cart/clear` | Clear cart | USER |

### Orders
| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| POST | `/api/orders` | Create order from cart | USER |
| GET | `/api/orders` | View order history | USER |

## Default Users
- **Admin**: username=`admin`, password=`admin`, email=`admin@mail.com`
- **User**: username=`testuser`, password=`testuser`, email=`test@mail.com`

## Testing
Run tests:
```bash
mvn test
```

Test coverage (32 tests):
- `UserServiceTest` - 7 tests (registration, login, user details)
- `JwtServiceTest` - 6 tests (token generation, validation)
- `BookTest` - 4 tests (model fields, equals/hashCode)
- `AppUserTest` - 5 tests (authorities, account status)
- `UserRoleTest` - 4 tests (enum values)
- `UserRoleConverterTest` - 6 tests (JPA converter)

## Project Structure
```
src/main/java/com/example/bookstore/
├── config/           # Security, Exception handling
├── controller/       # REST controllers, DTOs
├── model/            # JPA entities
├── repository/       # Spring Data JPA repositories
├── service/          # Business logic
└── BookstoreApplication.java
```

## Security Notes
- JWT_SECRET must be set via environment variable
- Passwords are BCrypt encoded
- Admin endpoints require ROLE_ADMIN
- All endpoints except auth, books (GET), swagger require authentication
