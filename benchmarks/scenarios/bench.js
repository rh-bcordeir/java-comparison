import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    bench: {
      executor: 'shared-iterations',
      vus: parseInt(__ENV.VUS || '100'),
      iterations: parseInt(__ENV.ITERATIONS || '1000'),
      maxDuration: __ENV.MAX_DURATION || '120s',
    },
  },
  summaryTrendStats: ['avg', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const r = http.get(__ENV.URL);
  check(r, { '200': (r) => r.status === 200 });
}

export function handleSummary(data) {
  return { [__ENV.SUMMARY_OUT]: JSON.stringify(data.metrics), stdout: '' };
}
