import { beforeEach, describe, expect, it, vi } from 'vitest';

// client.ts is platform-aware; pinning it to web exercises the real in-memory
// adapter, so the locking below runs against a genuine database rather than a stub.
vi.mock('react-native', () => ({ Platform: { OS: 'web' } }));

const { closeDatabase, getDatabase, withDatabaseClosed } = await import('./client');

describe('withDatabaseClosed', () => {
  beforeEach(async () => {
    // Open once before closing so the dynamic import of the adapter is already
    // cached. Otherwise a first-time open costs tens of milliseconds and the
    // timing assertion below would pass whether or not the lock works.
    await getDatabase();
    await closeDatabase();
  });

  it('holds off getDatabase until maintenance finishes', async () => {
    // The bug this prevents: a screen refetching on focus reopened SQLite while
    // a backup was copying the file, or while a restore was replacing it.
    const order: string[] = [];

    let maintenanceEntered!: () => void;
    const entered = new Promise<void>((resolve) => {
      maintenanceEntered = resolve;
    });
    let finishMaintenance!: () => void;
    const mayFinish = new Promise<void>((resolve) => {
      finishMaintenance = resolve;
    });

    const maintenance = withDatabaseClosed(async () => {
      order.push('maintenance:start');
      maintenanceEntered();
      await mayFinish;
      order.push('maintenance:end');
    });

    // Only queue the reader once maintenance definitely owns the lock.
    await entered;

    let readerResolved = false;
    const reader = getDatabase().then(() => {
      readerResolved = true;
      order.push('reader');
    });

    // Drain the event loop. A warmed, unblocked getDatabase resolves in single
    // -digit milliseconds, so this window is decisive rather than hopeful.
    await new Promise((resolve) => setTimeout(resolve, 250));
    expect(readerResolved).toBe(false);

    finishMaintenance();
    await Promise.all([maintenance, reader]);

    expect(order).toEqual(['maintenance:start', 'maintenance:end', 'reader']);
  });

  it('serializes concurrent maintenance operations', async () => {
    const order: string[] = [];

    await Promise.all([
      withDatabaseClosed(async () => {
        order.push('a:start');
        await new Promise((resolve) => setTimeout(resolve, 5));
        order.push('a:end');
      }),
      withDatabaseClosed(async () => {
        order.push('b:start');
        order.push('b:end');
      }),
    ]);

    // Never interleaved: both own the database file exclusively.
    expect(order).toEqual(['a:start', 'a:end', 'b:start', 'b:end']);
  });

  it('releases the lock when the operation throws', async () => {
    // A failed restore must not wedge the app into a state where nothing can
    // reopen the database.
    await expect(
      withDatabaseClosed(async () => {
        throw new Error('restore failed');
      })
    ).rejects.toThrow('restore failed');

    await expect(getDatabase()).resolves.toBeDefined();
  });

  it('propagates the operation error rather than a reopen error', async () => {
    await expect(
      withDatabaseClosed(async () => {
        throw new Error('the real problem');
      })
    ).rejects.toThrow('the real problem');
  });

  it('returns the operation result', async () => {
    await expect(withDatabaseClosed(async () => 'done')).resolves.toBe('done');
  });
});

describe('getDatabase', () => {
  beforeEach(async () => {
    await closeDatabase();
  });

  it('hands concurrent callers the same fully-initialized database', async () => {
    // Publishing the connection before the schema existed let a second caller
    // query a database with no tables yet ("no such table: garments").
    const [first, second] = await Promise.all([getDatabase(), getDatabase()]);

    expect(first).toBe(second);
    await expect(first.getAllAsync('SELECT * FROM garments')).resolves.toEqual([]);
  });
});
