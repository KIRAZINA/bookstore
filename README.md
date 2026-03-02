# Bookstore API

[![Build Status](https://github.com/KIRAZINA/bookstore/actions/workflows/maven.yml/badge.svg)](https://github.com/KIRAZINA/bookstore/actions/workflows/maven.yml) [![Java 17](https://img.shields.io/badge/Java-17-blue)](https://openjdk.java.net/projects/jdk/17/) [![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5-green)](https://spring.io/projects/spring-boot)

Spring Boot application for an online bookstore API with session-based authentication.

## Features
- **Authentication**: Session-based login/register with secure password encoding
- **CRUD**: Books (admin only for modifications)
- **Shopping Cart**: Add, update, remove items
- **Orders**: Create orders from cart, view order history
- **Database**: H2 in-memory (for dev), PostgreSQL ready (production)
- **Security**: Spring Security with session-based authentication, USER/ADMIN roles

## Setup
- JDK 17+
- Maven

## Steps
1. Clone the repo:
   ```bash
   git clone https://github.com/KIRAZINA/bookstore.git
   cd bookstore
   ```
2. Build and run:
   ```bash
   mvnw spring-boot:run
   ```
3. Server at http://localhost:8080
4. H2 Console: http://localhost:8080/h2-console (jdbc:h2:mem:testdb)

## API Endpoints

### Auth
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/auth/register` | Register new user |
| POST | `/api/auth/login` | Login (form-based) |
| GET | `/api/auth/me` | Get current user |

### Books
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/books` | List all books |
| GET | `/api/books?category=Fiction` | Filter by category |
| GET | `/api/books/search?search=java` | Search by title/author |
| POST | `/api/books` | Add new book (ADMIN) |
| DELETE | `/api/books/{id}` | Delete book (ADMIN) |
| PUT | `/api/books/{id}/stock?stock=10` | Update stock (ADMIN) |

### Cart
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/cart` | View cart |
| POST | `/api/cart/add?bookId=1&quantity=2` | Add item |
| PUT | `/api/cart/update?bookId=1&quantity=5` | Update quantity |
| DELETE | `/api/cart/remove?bookId=1` | Remove item |
| DELETE | `/api/cart/clear` | Clear cart |

### Orders
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/orders` | Create order from cart |
| GET | `/api/orders` | View order history |

## Default Users
- **Admin**: username=`admin`, password=`admin`, email=`admin@mail.com`
- **User**: username=`testuser`, password=`testuser`, email=`test@mail.com`

## Project Structure
```
src/main/java/com/example/bookstore/
├── config/           # Security, Exception handling
├── controller/       # REST controllers, DTOs
├── model/           # Entity classes
├── repository/       # Spring Data repositories
└── service/         # Business logic
```

## Simplifications Applied
- Removed JWT (using sessions instead)
- Removed pagination (simple list-based API)
- Removed EmailService (stub - add when needed)
- Clean code with SecurityUtils for user context
