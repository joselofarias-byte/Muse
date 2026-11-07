import test from "node:test";
import assert from "node:assert/strict";
import { migrations, validateMigrationChain } from "../dist/index.js";

test("database migrations form a contiguous chain", () => {
  validateMigrationChain(migrations);
  assert.deepEqual(migrations.map((migration) => migration.version), [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
});

test("Spanish defaults apply to existing databases without renaming customized plans", () => {
  const migration = migrations.find((item) => item.version === 10);
  assert.ok(migration);
  assert.match(migration.upSql, /alter table sessions alter column title set default 'Nueva conversación'/);
  assert.match(migration.upSql, /where id = 'free' and name = '免费 BYOK'/);
  assert.match(migration.upSql, /where id = 'pro' and name = 'Muse Pro'/);
  assert.doesNotMatch(migration.upSql, /update sessions/i);
});
