INSERT INTO app_user (username, password, email, role)
VALUES ('admin', '$2a$10$5Azz4P.SHqYkyaGKtyf8KeW4ijxcTFCqiu/qEbPMZJTunG8lU4nW.', 'admin@mail.com', 'ROLE_ADMIN');

INSERT INTO app_user (username, password, email, role)
VALUES ('testuser', '$2a$10$r/tN53V8P41iwK5A/dhWgO0A05ElmlvkJ0xPhk76IGvB/TmRrYOPS', 'test@mail.com', 'ROLE_USER');

INSERT INTO book (title, author, price, category, stock)
VALUES ('Book1', 'Author1', 10.99, 'Fiction', 10);

INSERT INTO book (title, author, price, category, stock)
VALUES ('Book2', 'Author2', 20.99, 'SciFi', 5);