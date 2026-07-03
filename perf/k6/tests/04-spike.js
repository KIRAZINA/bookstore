/**
 * Section 4: Spike Testing
 *
 * Simulates sudden traffic spikes: 100 → 1000 → 5000 → 100 users.
 * Verify recovery, autoscaling, and stability after spike.
 *
 * Run:
 *   k6 run perf/k6/tests/04-spike.js
 */
import http from 'k6/http';
import { sleep } from 'k6';
import { browseScenario, seedCatalog, setupAdmin } from '../lib/common.js';

export const options = {
  scenarios: {
    spike: {
      executor: 'ramping-vus',
      startVUs: 100,
      stages: [
        { duration: '1m', target: 100 },
        { duration: '30s', target: 1000 },
        { duration: '2m', target: 1000 },
        { duration: '30s', target: 5000 },
        { duration: '1m', target: 5000 },
        { duration: '30s', target: 100 },
        { duration: '3m', target: 100 },
        { duration: '1m', target: 0 },
      ],
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.20'],
    http_req_duration: ['p(99)<15000'],
  },
};

export function setup() {
  const adminToken = setupAdmin(http);
  if (adminToken) {
    seedCatalog(http, adminToken);
  }
  return {};
}

export default function () {
  browseScenario(http);
  sleep(0.2);
}
