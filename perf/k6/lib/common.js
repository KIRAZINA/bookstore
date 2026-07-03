/**
 * Shared configuration for bookstore k6 performance tests.
 *
 * Environment variables:
 *   BASE_URL          - API base URL (default: http://localhost:8080)
 *   ADMIN_USER        - Admin username for seeding (default: perf-admin)
 *   ADMIN_PASSWORD    - Admin password (default: PerfTest123!)
 *   RATE_LIMITING     - Document only; disable via APP_RATE_LIMITING_ENABLED=false on server
 */

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const DEFAULT_PASSWORD = __ENV.USER_PASSWORD || 'testuser';
export const ADMIN_USER = __ENV.ADMIN_USER || 'admin';
export const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'admin123';

export const JSON_HEADERS = {
  'Content-Type': 'application/json',
  Accept: 'application/json',
};

export const THRESHOLDS_BASELINE = {
  http_req_failed: ['rate<0.01'],
  http_req_duration: ['p(95)<2000', 'p(99)<5000'],
};

export const THRESHOLDS_LOAD = {
  http_req_failed: ['rate<0.05'],
  http_req_duration: ['p(95)<3000', 'p(99)<8000'],
};

export function authHeaders(token) {
  return {
    ...JSON_HEADERS,
    Authorization: `Bearer ${token}`,
  };
}

export function registerAndLogin(http, username, password) {
  const pwd = password || DEFAULT_PASSWORD;
  const registerRes = http.post(
    `${BASE_URL}/api/v1/auth/register`,
    JSON.stringify({
      username,
      password: pwd,
      email: `${username}@perf.test`,
    }),
    { headers: JSON_HEADERS, tags: { name: 'auth_register' } }
  );

  if (registerRes.status !== 201 && registerRes.status !== 200) {
    const loginRes = http.post(
      `${BASE_URL}/api/v1/auth/login`,
      JSON.stringify({ username, password: pwd }),
      { headers: JSON_HEADERS, tags: { name: 'auth_login' } }
    );
    if (loginRes.status !== 200) {
      return null;
    }
    return JSON.parse(loginRes.body).token;
  }

  const loginRes = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ username, password: pwd }),
    { headers: JSON_HEADERS, tags: { name: 'auth_login' } }
  );
  if (loginRes.status !== 200) {
    return null;
  }
  return JSON.parse(loginRes.body).token;
}

export function login(http, username, password) {
  const loginRes = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ username, password }),
    { headers: JSON_HEADERS, tags: { name: 'auth_login' } }
  );
  if (loginRes.status !== 200) {
    return null;
  }
  return JSON.parse(loginRes.body).token;
}

export function browseScenario(http) {
  http.get(`${BASE_URL}/api/v1/books`, { tags: { name: 'books_list' } });
  http.get(`${BASE_URL}/api/v1/books/search?search=Book`, { tags: { name: 'books_search' } });
  http.get(`${BASE_URL}/api/v1/books/category?category=Fiction`, { tags: { name: 'books_category' } });
}

export function checkoutScenario(http, token, bookId) {
  const headers = authHeaders(token);

  http.post(
    `${BASE_URL}/api/v1/cart/items`,
    JSON.stringify({ bookId, quantity: 1 }),
    { headers, tags: { name: 'cart_add' } }
  );

  const orderRes = http.post(`${BASE_URL}/api/v1/orders`, null, {
    headers,
    tags: { name: 'order_create' },
  });

  if (orderRes.status !== 200) {
    return;
  }

  const orderId = JSON.parse(orderRes.body).id;
  http.post(
    `${BASE_URL}/api/v1/payments/${orderId}/process`,
    JSON.stringify({ paymentMethod: 'CREDIT_CARD' }),
    { headers, tags: { name: 'payment_process' } }
  );
}

export function seedCatalog(http, adminToken) {
  const headers = authHeaders(adminToken);
  const books = [
    { title: 'Load Test Alpha', author: 'Author A', price: 14.99, category: 'Fiction', initialStock: 10000 },
    { title: 'Load Test Beta', author: 'Author B', price: 24.99, category: 'SciFi', initialStock: 10000 },
    { title: 'Load Test Gamma', author: 'Author C', price: 9.99, category: 'Fiction', initialStock: 10000 },
  ];

  const bookIds = [];
  for (const book of books) {
    const res = http.post(`${BASE_URL}/api/v1/books`, JSON.stringify(book), {
      headers,
      tags: { name: 'admin_create_book' },
    });
    if (res.status === 201) {
      const bookId = JSON.parse(res.body).id;
      http.put(
        `${BASE_URL}/api/v1/books/${bookId}/stock`,
        JSON.stringify({ stock: book.initialStock || 10000 }),
        { headers, tags: { name: 'admin_update_stock' } }
      );
      bookIds.push(bookId);
    }
  }
  return bookIds;
}

export function setupAdmin(http) {
  return login(http, ADMIN_USER, ADMIN_PASSWORD);
}
