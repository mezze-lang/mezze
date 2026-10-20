package com.mezze.interpreter.nodes.builtin.effects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link FilesystemNatives.OpenResources} — what a disposing context releases.
 *
 * The wiring is tested through the guest, in FileWatchEffectTest: a leaked
 * watch's thread must be gone once its context closes. That test can only
 * witness the WATCHES arm, though, because a watch thread is the one resource
 * GC cannot reclaim. A leaked channel or lock is closed by FileDescriptor's
 * Cleaner on the next GC, so a guest-level test of those arms passes whether
 * or not dispose did the work — which is worse than no test.
 *
 * So the other two arms are tested here instead, directly and without a
 * context: build the registry, hand it real OS resources, close it, and ask
 * the resources whether they are open. No GC involved, nothing to race.
 *
 * In-package by necessity — OpenResources and the handle constructors are
 * package-private, and widening them for a test would be the wrong trade.
 */
class OpenResourcesTest {

    @Test
    void closeReleasesAnUnclosedFileChannel(@TempDir Path tmp) throws IOException {
        Path file = Files.createFile(tmp.resolve("leaked.bin"));
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
        FilesystemNatives.MezzeFileHandle handle =
            new FilesystemNatives.MezzeFileHandle(channel, file.toString());

        FilesystemNatives.OpenResources open = new FilesystemNatives.OpenResources();
        open.files.add(handle);
        assertTrue(channel.isOpen(), "precondition: the channel must start open");

        open.close();

        assertFalse(channel.isOpen(), "dispose must close a channel the guest never closed");
        assertTrue(handle.isClosed(), "the handle must be marked closed, so a later close() no-ops");
        assertTrue(open.files.isEmpty(), "the registry must not keep the handle after releasing it");
    }

    /**
     * A lock is released AND its channel closed. Releasing without closing
     * would leave the fd, and closing without releasing would work by
     * accident — a channel close drops its locks — but only on this JVM's
     * implementation, and it would stop being true the moment the handle held
     * a lock on a channel it did not own.
     */
    @Test
    void closeReleasesAnUnreleasedLock(@TempDir Path tmp) throws IOException {
        Path file = Files.createFile(tmp.resolve("app.lock"));
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
        java.nio.channels.FileLock lock = channel.lock(0L, Long.MAX_VALUE, false);
        FilesystemNatives.MezzeLockHandle handle =
            new FilesystemNatives.MezzeLockHandle(lock, channel, file.toString());

        FilesystemNatives.OpenResources open = new FilesystemNatives.OpenResources();
        open.locks.add(handle);
        assertTrue(lock.isValid(), "precondition: the lock must start held");

        open.close();

        assertFalse(lock.isValid(), "dispose must release a lock the guest never unlocked");
        assertFalse(channel.isOpen(), "and close the channel that held it, or the fd leaks");
        assertTrue(open.locks.isEmpty(), "the registry must not keep the handle after releasing it");
    }

    /**
     * Closing twice must be safe: disposeContext can run after a guest that
     * did call close/unlock, and a second release on an already-released
     * resource throws.
     */
    @Test
    void closeIsSafeOverAlreadyReleasedResources(@TempDir Path tmp) throws IOException {
        Path file = Files.createFile(tmp.resolve("tidy.bin"));
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
        FilesystemNatives.MezzeFileHandle handle =
            new FilesystemNatives.MezzeFileHandle(channel, file.toString());
        channel.close();
        handle.closed.set(true);          // what FilesystemNatives.close() does

        FilesystemNatives.OpenResources open = new FilesystemNatives.OpenResources();
        open.files.add(handle);

        open.close();                     // must not throw
        open.close();                     // and must be idempotent

        assertTrue(open.files.isEmpty());
    }

    /** An empty registry is the common case — most programs leak nothing. */
    @Test
    void closeOnAnEmptyRegistryDoesNothing() {
        FilesystemNatives.OpenResources open = new FilesystemNatives.OpenResources();
        open.close();
        assertTrue(open.files.isEmpty() && open.locks.isEmpty() && open.watches.isEmpty());
    }
}
