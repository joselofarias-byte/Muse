import { PostgresDatabaseClient, PostgresJobQueue, applyMigrations, migrations, type PgPoolLike } from "@muse/database";
import { InMemoryJobQueue, JobWorker, RedisJobQueue, type JobQueue } from "@muse/jobs";

const intervalMs = Math.max(250, Number(process.env.WORKER_POLL_INTERVAL_MS ?? 5000));
const workerId = process.env.WORKER_ID ?? `worker-${process.pid}`;
type ClosableQueue = JobQueue & { close: () => Promise<void> };

function withClose(queue: JobQueue, close: () => Promise<void>): ClosableQueue {
  return { enqueue: queue.enqueue.bind(queue), claim: queue.claim.bind(queue), complete: queue.complete.bind(queue), fail: queue.fail.bind(queue), close };
}

async function createQueue(): Promise<ClosableQueue> {
  const redisUrl = process.env.REDIS_URL;
  if (redisUrl) {
    const redis = await import("redis");
    const client = redis.createClient({ url: redisUrl });
    await client.connect();
    return withClose(new RedisJobQueue(client), async () => { await client.quit(); });
  }
  const databaseUrl = process.env.DATABASE_URL;
  if (databaseUrl) {
    const pg = await import("pg");
    const pool = new pg.Pool({ connectionString: databaseUrl, max: Number(process.env.DB_POOL_MAX ?? 5), idleTimeoutMillis: 30_000, connectionTimeoutMillis: 10_000 });
    const database = new PostgresDatabaseClient(pool as unknown as PgPoolLike);
    await applyMigrations(database, migrations);
    return withClose(new PostgresJobQueue(database), async () => { await pool.end(); });
  }
  return withClose(new InMemoryJobQueue(), async () => {});
}

function skip(job: { id: string; type: string }): void {
  // F-42: Implementación de marcador de posición — Requiere que el proveedor de servicios backend lo implemente;Manténgase libre de efectos secundarios,Simplemente inicia sesión y salta,Evite completar por error/Falló por error.
  console.warn(`[muse-worker] ${job.type} handler is a no-op placeholder; job ${job.id} skipped (requires backend service provider)`);
}

const queue = await createQueue();

const worker = new JobWorker(queue, {
  // F-42: memory_extract / rag_index Cuando se ejecuta en Android(Host modo)Implementado por la aplicación muse en bucle cerrado.;
  // web worker Actualmente no existe ningún servicio backend correspondiente.,Se omite el marcador de posición.TODO(backend): Acceda a los servicios de extracción de memoria/indexación RAG.
  memory_extract: async (job) => skip(job),
  rag_index: async (job) => skip(job),
  // TODO(backend): Acceder al servicio de análisis de documentos(subir → Extraer texto).
  document_parse: async (job) => skip(job),
  // TODO(backend): Accede al servicio de encuestas de medios.
  media_poll: async (job) => skip(job),
  // TODO(backend): Acceda al servicio de entrega de notificaciones.
  notification: async (job) => skip(job),
});
console.log(`Muse worker started; poll interval=${intervalMs}ms; id=${workerId}; queue=${process.env.REDIS_URL ? "redis" : process.env.DATABASE_URL ? "postgres" : "memory"}`);
let stopping = false;
const timer = setInterval(() => { if (!stopping) void worker.runOnce(workerId).catch((error) => console.error("worker iteration failed", error)); }, intervalMs);
async function shutdown(): Promise<void> { if (stopping) return; stopping = true; clearInterval(timer); await queue.close(); process.exit(0); }
process.once("SIGTERM", () => { void shutdown(); });
process.once("SIGINT", () => { void shutdown(); });
