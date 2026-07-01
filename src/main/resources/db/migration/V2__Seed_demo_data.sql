-- Seed admin user
INSERT INTO app_user (username, password, email, role)
SELECT 'admin', '$2a$10$5Azz4P.SHqYkyaGKtyf8KeW4ijxcTFCqiu/qEbPMZJTunG8lU4nW.', 'admin@mail.com', 'ROLE_ADMIN'
WHERE NOT EXISTS (SELECT 1 FROM app_user WHERE username = 'admin');

-- Seed test user
INSERT INTO app_user (username, password, email, role)
SELECT 'testuser', '$2a$10$r/tN53V8P41iwK5A/dhWgO0A05ElmlvkJ0xPhk76IGvB/TmRrYOPS', 'test@mail.com', 'ROLE_USER'
WHERE NOT EXISTS (SELECT 1 FROM app_user WHERE username = 'testuser');

-- Seed books
INSERT INTO book (title, author, price, category, stock, version)
SELECT 'Book1', 'Author1', 10.99, 'Fiction', 10, 0
WHERE NOT EXISTS (SELECT 1 FROM book WHERE title = 'Book1');

INSERT INTO book (title, author, price, category, stock, version)
SELECT 'Book2', 'Author2', 20.99, 'SciFi', 5, 0
WHERE NOT EXISTS (SELECT 1 FROM book WHERE title = 'Book2');
