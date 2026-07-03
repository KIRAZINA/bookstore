/**
 * Section 5: Endurance Testing
 *
 * Sustained realistic traffic for 30–60 minutes.
 * Detect memory leaks, connection leaks, latency drift, Kafka lag growth.
 *
 * Run (30 min default, override with ENDURANCE_DURATION):
 *   k6 run perf/k6/tests/05-endurance.js
 *   ENDURANCE_DURATION=60m k6 run perf/k6/tests/05-endurance.js
 */
import http from 'k6/http';
import { sleep } from 'k6';
import {
  browseScenario,
  checkoutScenario,
  registerAndLogin,
  seedCatalog,
  setupAdmin,
} from '../lib/common.js';

const duration = __ENV.ENDURANCE_DURATION || '30m';

export const options = {
  scenarios: {
    endurance: {
      executor: 'constant-vus',
      vus: 100,
      duration,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<5000', 'avg<2000'],
  },
};

export function setup() {
  const adminToken = setupAdmin(http);
  const bookIds = adminToken ? seedCatalog(http, adminToken) : [1, 2];
  return { bookIds };
}

export default function (data) {
  if (__ITER % 10 === 0) {
    const token = registerAndLogin(http, `endure-${__VU}-${__ITER}`);
    if (token) {
      checkoutScenario(http, token, data.bookIds[__VU % data.bookIds.length]);
    }
  } else {
    browseScenario(http);
  }
  sleep(1 + Math.random());
}
