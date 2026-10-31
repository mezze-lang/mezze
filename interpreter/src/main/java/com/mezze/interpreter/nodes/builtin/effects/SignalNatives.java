package com.mezze.interpreter.nodes.builtin.effects;

import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.concurrent.CountDownLatch;

/**
 * Native for the Signal effect — std/effects/signal.mz.
 *
 * {@code __native_signal_await_interrupt} parks the calling thread until the
 * process receives SIGINT (Ctrl-C) OR SIGTERM (the signal `kill`, `docker stop`,
 * and process managers send to ask for shutdown), then returns so a server can
 * shut down gracefully — the mezze equivalent of awaiting
 * {@code tokio::signal::ctrl_c()} (plus the SIGTERM a real service also wants).
 *
 * Only the {@code SystemSignal} impl calls this; {@code ImmediateSignal} returns
 * in pure mezze, so a test or a non-blocking tour never parks here.
 *
 * <h2>SIGINT ownership</h2>
 *
 * In a standalone / native run the guest process owns SIGINT and this handler
 * fires. Under {@code mez dev} the PARENT CLI owns SIGINT and forwards an
 * INTERRUPT frame that cancels the program instead (see
 * {@code WireRunner.SigintForwarder}); that cancellation interrupts this park,
 * which we treat as the signal and return. Either way Ctrl-C stops the server —
 * only the standalone path runs the in-guest graceful path to completion.
 */
public final class SignalNatives {

    private SignalNatives() {}

    @TruffleBoundary
    public static MezzeRecord awaitInterrupt(MezzeRecord args) {
        final CountDownLatch latch = new CountDownLatch(1);
        // Install handlers for BOTH SIGINT (Ctrl-C) and SIGTERM (kill / `docker
        // stop` / a process manager asking to shut down). Either releases the
        // latch, so the server parks until whichever arrives first. Each is
        // installed INDEPENDENTLY: a platform may offer one and not the other
        // (e.g. Windows refuses these), and only if NEITHER installs do we
        // decline to park and return at once — the caller then shuts down now
        // rather than hanging forever.
        final java.util.List<Runnable> restores = new java.util.ArrayList<>();
        for (String name : new String[] { "INT", "TERM" }) {
            try {
                final sun.misc.Signal sig = new sun.misc.Signal(name);
                final sun.misc.SignalHandler previous = sun.misc.Signal.handle(sig, s -> latch.countDown());
                restores.add(() -> {
                    try {
                        sun.misc.Signal.handle(sig, previous);
                    } catch (Throwable ignored) {
                        // best-effort restore of the previous handler
                    }
                });
            } catch (Throwable ignored) {
                // this signal is unavailable/refused on this platform; try the next.
            }
        }
        if (restores.isEmpty()) {
            return MezzeRecord.empty();
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            // A Truffle cancel (mez dev's INTERRUPT frame) lands here — treat it
            // as the shutdown request and return so cleanup can run.
            Thread.currentThread().interrupt();
        } finally {
            for (Runnable restore : restores) restore.run();
        }
        return MezzeRecord.empty();
    }
}
