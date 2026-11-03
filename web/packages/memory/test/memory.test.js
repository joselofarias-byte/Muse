import test from "node:test";
import assert from "node:assert/strict";
import { MemoryStore } from "../dist/index.js";

test("memory search is scoped and protects pinned critical facts", () => {
  const store = new MemoryStore();
  const space = store.createSpace("a1", "vida");
  const fact = store.addFact({ accountId: "a1", assistantId: "as1", spaceId: space.id, scope: "as1", content: "A los usuarios les gusta el bambú.", importance: 2, confidence: 1, source: "explicit" });
  store.addFact({ accountId: "a2", assistantId: "as1", spaceId: space.id, scope: "as1", content: "A los usuarios les gusta el bambú.", importance: 2, confidence: 1, source: "explicit" });
  assert.deepEqual(store.search("a1", "bambú", "as1", space.id).map((item) => item.id), [fact.id]);
  assert.equal(store.decay(), 0);
  assert.equal(store.markHit(fact.id).hitCount, 1);
});
