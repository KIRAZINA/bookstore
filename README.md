# Bookstore API

[![Build Status](https://github.com/KIRAZINA/bookstore/actions/workflows/maven.yml/badge.svg)](https://github.com/KIRAZINA/bookstore/actions/workflows/maven.yml) [![Java 17](https://img.shields.io/badge/Java-17-blue)](https://openjdk.java.net/projects/jdk/17/) [![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.x-green)](https://spring.io/projects/spring-boot)

     Spring Boot application for an online bookstore API with JWT authentication.

     ## Features
    - **Authentication**: JWT (login/register).
    - **CRUD**: Books (admin only), shopping cart, orders.
    - **Database**: H2 in-memory (for dev).
    - **Security**: Spring Security, USER/ADMIN roles.

## Setup
- JDK 17+
- Maven

## Technologies
- Java 17
- Spring Boot 3.x
- Spring Security + JJWT
- Hibernate/JPA
- Maven
- H2 Database

## Steps
1. Clone the repo:
   git clone https://github.com/KIRAZINA/bookstore.git
   cd bookstore
2. Build and run:
- mvnw spring-boot:run
- Server at http://localhost:8080

## API Endpoints
- **Auth**:
- POST `/api/auth/register` — registration {username, password, email}
- POST `/api/auth/login` — login, returns JWT
- **Books** (with Bearer JWT):
- GET `/api/books` — list of books
- POST `/api/books` — add (ADMIN)
- PUT `/api/books/{id}` — update (ADMIN)
- DELETE `/api/books/{id}` — delete (ADMIN)
- **Cart**:
- POST `/api/cart/add` — add book {bookId, quantity}
- GET `/api/cart` — view
- **Orders**:
- POST `/api/orders` — create from cart
- GET `/api/orders` — list

## Testing
- Curl examples in [docs/tests.md](docs/tests.md) (create the file if necessary).
- H2 Console: http://localhost:8080/h2-console (jdbc:h2:mem:testdb)