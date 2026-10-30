package com.mezze.interpreter.nodes.builtin.effects;

import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.concurrent.CountDownLatch;

/**
 * Native for the Signal effect — std/effects/signal.mz.
 *
 * {@code __native_signal_await_interrupt} parks the calling thread until the
 * process receives SIGINT (Ctrl-C), then returns so a server can shut down
 * gracefully — the mezze equivalent of awaiting {@code tokio::signal::ctrl_c()}.
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
        sun.misc.Signal sig;
        sun.misc.SignalHandler previous;
        try {
            sig = new sun.misc.Signal("INT");
            previous = sun.misc.Signal.handle(sig, s -> latch.countDown());
        } catch (Throwable t) {
            // sun.misc.Signal unavailable or refused (e.g. Windows): we cannot
            // wait for SIGINT, so return at once rather than hang forever — the
            // caller then shuts the server down immediately.
            return MezzeRecord.empty();
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            // A Truffle cancel (mez dev's INTERRUPT frame) lands here — treat it
            // as the shutdown request and return so cleanup can run.
            Thread.currentThread().interrupt();
        } finally {
            try {
                sun.misc.Signal.handle(sig, previous);
            } catch (Throwable ignored) {
                // best-effort restore of the previous handler
            }
        }
        return MezzeRecord.empty();
    }
}
