import assert from 'node:assert/strict';
import test from 'node:test';
import { violation } from '../scripts/check-boundaries.mjs';

test('production build rejects demo data and persistent browser business state', () => {
  assert.match(violation('data.js', ''), /demo data/);
  assert.match(violation('pages/home/index.js', 'localStorage.setItem("user", "x")'), /browser storage/);
});

test('production build rejects direct API calls in components', () => {
  assert.match(violation('components/common/card.js', 'fetch("/api/business/v1/home")'), /components/);
  assert.match(violation('components/common/card.js', 'import { get } from "../../api/client.js"'), /components/);
  assert.equal(violation('pages/home/index.js', 'import { get } from "../../api/client.js"'), null);
});
