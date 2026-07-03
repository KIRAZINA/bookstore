/**
 * Section 6: Inventory Contention
 *
 * Many concurrent checkouts against limited stock to measure
 * reservation throughput, lock contention, and overselling prevention.
 *
 * Run:
 *   k6 run perf/k6/tests/06-inventory-contention.js
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import {
  authHeaders,
  registerAndLogin,
  seedCatalog,
  setupAdmin,
  BASE_URL,
} from '../lib/common.js';

export const options = {
  scenarios: {
    contention: {
      executor: 'shared-iterations',
      vus: 200,
      iterations: 200,
      maxDuration: '5m',
    },
  },
  thresholds: {
    'http_req_duration{name:order_create}': ['p(95)<10000'],
  },
};

export function setup() {
  const adminToken = setupAdmin(http);
  if (!adminToken) {
    return { bookId: 1, stock: 100 };
  }

  const book = {
    title: 'Contention Book',
    author: 'Perf Author',
    price: 5.99,
    category: 'Performance',
    initialStock: 100,
  };
  const res = http.post(`${BASE_URL}/api/v1/books`, JSON.stringify(book), {
    headers: authHeaders(adminToken),
    tags: { name: 'admin_create_book' },
  });

  const bookId = res.status === 201 ? JSON.parse(res.body).id : 1;
  return { bookId, stock: 100, adminToken };
}

export default function (data) {
  const username = `contention-${__VU}-${__ITER}`;
  const token = registerAndLogin(http, username);
  if (!token) {
    return;
  }

  const headers = authHeaders(token);

  http.post(
    `${BASE_URL}/api/v1/cart/items`,
    JSON.stringify({ bookId: data.bookId, quantity: 1 }),
    { headers, tags: { name: 'cart_add' } }
  );

  const orderRes = http.post(`${BASE_URL}/api/v1/orders`, null, {
    headers,
    tags: { name: 'order_create' },
  });

  check(orderRes, {
    'order created or stock exhausted': (r) => r.status === 200 || r.status === 400 || r.status === 409,
  });

  sleep(0.1);
}

export function teardown(data) {
  if (!data.adminToken) {
    return;
  }
  const invRes = http.get(`${BASE_URL}/api/v1/admin/inventory/${data.bookId}`, {
    headers: authHeaders(data.adminToken),
  });
  if (invRes.status === 200) {
    console.log(`Final inventory state: ${invRes.body}`);
  }
}
