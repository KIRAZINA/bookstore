/**
 * Section 1: Performance Baseline
 *
 * Low concurrency, measures latency distribution for realistic business operations.
 *
 * Run:
 *   k6 run perf/k6/tests/01-baseline.js
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import {
  BASE_URL,
  THRESHOLDS_BASELINE,
  browseScenario,
  checkoutScenario,
  registerAndLogin,
  seedCatalog,
  setupAdmin,
} from '../lib/common.js';

export const options = {
  scenarios: {
    baseline_browse: {
      executor: 'constant-vus',
      vus: 5,
      duration: '2m',
      exec: 'browse',
    },
    baseline_checkout: {
      executor: 'constant-vus',
      vus: 3,
      duration: '2m',
      exec: 'checkout',
      startTime: '30s',
    },
  },
  thresholds: THRESHOLDS_BASELINE,
};

export function setup() {
  const adminToken = setupAdmin(http);
  const bookIds = adminToken ? seedCatalog(http, adminToken) : [1, 2];
  return { bookIds: bookIds.length ? bookIds : [1] };
}

export function browse() {
  browseScenario(http);
  sleep(1);
}

export function checkout(data) {
  const username = `baseline-${__VU}-${__ITER}`;
  const token = registerAndLogin(http, username);
  if (!token) {
    return;
  }
  const bookId = data.bookIds[__ITER % data.bookIds.length];
  checkoutScenario(http, token, bookId);
  sleep(2);
}

export default function () {
  browse();
}
