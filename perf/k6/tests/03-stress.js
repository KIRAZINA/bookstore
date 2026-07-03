/**
 * Section 3: Stress Testing
 *
 * Gradually increases traffic until degradation is observed.
 * Monitor: error rate, p99 latency, connection pool, Kafka lag, HPA.
 *
 * Run:
 *   k6 run perf/k6/tests/03-stress.js
 */
import http from 'k6/http';
import { sleep } from 'k6';
import { browseScenario, checkoutScenario, registerAndLogin, seedCatalog, setupAdmin } from '../lib/common.js';

export const options = {
  scenarios: {
    stress_ramp: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: 3000,
      stages: [
        { duration: '2m', target: 50 },
        { duration: '2m', target: 100 },
        { duration: '2m', target: 200 },
        { duration: '2m', target: 400 },
        { duration: '2m', target: 600 },
        { duration: '2m', target: 800 },
        { duration: '2m', target: 1000 },
        { duration: '5m', target: 1000 },
        { duration: '2m', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: [{ threshold: 'rate<0.50', abortOnFail: false }],
  },
};

export function setup() {
  const adminToken = setupAdmin(http);
  const bookIds = adminToken ? seedCatalog(http, adminToken) : [1];
  return { bookIds };
}

export default function (data) {
  if (__ITER % 3 === 0) {
    const token = registerAndLogin(http, `stress-${__VU}-${__ITER}`);
    if (token) {
      checkoutScenario(http, token, data.bookIds[0]);
    }
  } else {
    browseScenario(http);
  }
  sleep(0.1);
}
