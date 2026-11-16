package io.github.download.maven.plugin.internal;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

/**
 * Holds a lock from another thread until closed, simulating a concurrent download:
 * <pre>
 * try (HeldLock ignored = HeldLock.on(lock)) {
 *     // the current thread can't acquire the lock here
 * }
 * </pre>
 */
public final class HeldLock implements AutoCloseable {

    private final Thread holder;

    private final CountDownLatch release;

    private HeldLock(final Thread holder, final CountDownLatch release) {
        this.holder = holder;
        this.release = release;
    }

    /**
     * Acquires the lock in a new thread and returns once it's held.
     */
    public static HeldLock on(final Lock lock) throws InterruptedException {
        final CountDownLatch locked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread holder = new Thread(() -> {
            lock.lock();
            try {
                locked.countDown();
                release.await();
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }, "held-lock");
        holder.setDaemon(true);
        holder.start();
        if (!locked.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("could not acquire the lock in time");
        }
        return new HeldLock(holder, release);
    }

    /**
     * Releases the lock and waits for the holding thread to finish.
     */
    @Override
    public void close() throws InterruptedException {
        this.release.countDown();
        this.holder.join();
    }
}
