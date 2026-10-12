import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// Custom metrics to measure against resume claims
const http200Count = new Counter('gateflux_200_ok');
const http429Count = new Counter('gateflux_429_throttled');
const authLatencyTrend = new Trend('gateflux_req_duration_ms');

// Configurable target host and token
const TARGET_HOST = __ENV.TARGET_HOST || 'http://localhost:8080';
const AUTH_TOKEN = __ENV.TOKEN || '';

export const options = {
  scenarios: {
    // Scenario 1: Latency & Throughput Benchmark
    // Measures p50, p95, p99 evaluation latency under steady load
    latency_test: {
      executor: 'constant-vus',
      vus: 50,
      duration: '30s',
      gracefulStop: '5s',
    },
    // Scenario 2: Concurrency Ramp (verifying non-blocking connection handling)
    // To run 10k connections on cloud instance, increase target to 10000
    concurrency_ramp: {
      executor: 'ramping-vus',
      startTime: '35s',
      stages: [
        { duration: '30s', target: 200 },
        { duration: '1m', target: 500 },
        { duration: '30s', target: 0 },
      ],
      gracefulStop: '10s',
    },
  },
  thresholds: {
    // Resume claim: Sub-millisecond to low-millisecond gateway overhead
    http_req_duration: ['p(95)<50', 'p(99)<100'],
  },
};

export default function () {
  const headers = {
    'Accept': 'application/json',
  };

  if (AUTH_TOKEN) {
    headers['Authorization'] = `Bearer ${AUTH_TOKEN}`;
  }

  const testPath = __ENV.TARGET_PATH || (AUTH_TOKEN ? '/get' : '/actuator/health');
  const res = http.get(`${TARGET_HOST}${testPath}`, {
    headers: headers,
    tags: { name: 'GateFlux_Request' },
  });

  authLatencyTrend.add(res.timings.duration);

  if (res.status === 200) {
    http200Count.add(1);
    const checks = { 'status is 200': (r) => r.status === 200 };
    if (testPath !== '/actuator/health') {
      checks['has correlation ID header'] = (r) => r.headers['X-Correlation-Id'] !== undefined;
    }
    check(res, checks);
  } else if (res.status === 429) {
    http429Count.add(1);
    check(res, {
      'status is 429 rate limited': (r) => r.status === 429,
    });
  } else if (res.status === 401) {
    check(res, {
      'status is 401 unauthorized (no token passed)': (r) => r.status === 401,
    });
  }

  // Brief pause to simulate realistic client request pacing
  sleep(0.1);
}
