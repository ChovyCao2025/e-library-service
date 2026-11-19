import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  scenarios: {
    catalog: {
      executor: 'constant-vus',
      vus: 5,
      duration: '20s',
      exec: 'catalog',
    },
    hotspot: {
      executor: 'per-vu-iterations',
      vus: 20,
      iterations: 1,
      maxDuration: '20s',
      exec: 'hotspot',
    },
  },
  thresholds: {
    http_req_duration: ['p(99)<1000'],
  },
};

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';

export function catalog() {
  const response = http.get(`${baseUrl}/api/v1/books?size=20`);
  check(response, { 'catalog is 200': (r) => r.status === 200 });
  sleep(0.1);
}

export function hotspot() {
  const userId = String(50000 + __VU);
  const response = http.post(
    `${baseUrl}/api/v1/loans`,
    JSON.stringify({ bookId: '2000001' }),
    {
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': `k6-hotspot-${__VU}`,
        'X-User-Id': userId,
        'X-Role': 'USER',
      },
    },
  );
  check(response, {
    'borrow decided': (r) => [201, 409, 503].includes(r.status),
  });
}
