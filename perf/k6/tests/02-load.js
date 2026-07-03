/**
 * Section 2: Load Testing
 *
 * Simulates 50 → 1000 concurrent users with mixed browse/checkout workload.
 * Disable rate limiting on server for capacity testing:
 *   APP_RATE_LIMITING_ENABLED=false mvn spring-boot:run -Dspring-boot.run.profiles=perf
 *
 * Run:
 *   k6 run perf/k6/tests/02-load.js
 */
import http from 'k6/http';
import { sleep } from 'k6';
import {
  THRESHOLDS_LOAD,
  browseScenario,
  checkoutScenario,
  registerAndLogin,
  seedCatalog,
  setupAdmin,
} from '../lib/common.js';

const stages = [
  { duration: '2m', target: 50 },
  { duration: '3m', target: 50 },
  { duration: '2m', target: 100 },
  { duration: '3m', target: 100 },
  { duration: '2m', target: 250 },
  { duration: '3m', target: 250 },
  { duration: '2m', target: 500 },
  { duration: '3m', target: 500 },
  { duration: '2m', target: 1000 },
  { duration: '3m', target: 1000 },
  { duration: '2m', target: 0 },
];

export const options = {
  scenarios: {
    mixed_load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages,
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    ...THRESHOLDS_LOAD,
    'http_req_duration{name:order_create}': ['p(95)<5000'],
    'http_req_duration{name:payment_process}': ['p(95)<5000'],
    'http_req_failed{name:order_create}': ['rate<0.10'],
  },
};

export function setup() {
  const adminToken = setupAdmin(http);
  const bookIds = adminToken ? seedCatalog(http, adminToken) : [1, 2, 3];
  return { bookIds };
}

export default function (data) {
  // 70% browse, 30% checkout — realistic e-commerce traffic mix
  if (Math.random() < 0.7) {
    browseScenario(http);
    sleep(Math.random() * 2 + 0.5);
    return;
  }

  const username = `load-${__VU}-${Date.now()}`;
  const token = registerAndLogin(http, username);
  if (!token) {
    sleep(1);
    return;
  }

  const bookId = data.bookIds[__VU % data.bookIds.length];
  checkoutScenario(http, token, bookId);
  sleep(Math.random() * 3 + 1);
}
