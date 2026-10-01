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

test('components cannot import pages and API modules cannot depend on DOM', () => {
  assert.match(violation('components/layout/shell.js', 'import { mount } from "../../pages/home/index.js"'), /pages/);
  assert.match(violation('api/home.js', 'document.querySelector("main")'), /DOM/);
  assert.equal(violation('components/common/status.js', 'root.ownerDocument.createElement("p")'), null);
});
