package com.cabin.hardware.replacement;

/** Observes the verified sleep sequence; no OS sleep or MCU acknowledgments are emitted.
 * Actual acknowledgments require the vendor readiness/wake contract, still unverified.
 */
public final class PowerSequence {
    public enum State { UNKNOWN, PREPARING, READY_REQUESTED, SLEEP_REQUESTED, OUT_OF_ORDER }
    private State state = State.UNKNOWN;
    public synchronized void accept(byte[] payload) {
        if (payload.length != 4 || payload[0] != 1 || payload[1] != 0 || (payload[2] & 255) != 0x89) return;
        switch (payload[3] & 255) {
            case 0x53: state = State.PREPARING; break;
            case 0x54:
                state = state == State.PREPARING || state == State.READY_REQUESTED ? State.READY_REQUESTED : State.OUT_OF_ORDER;
                break;
            case 0x55:
                state = state == State.READY_REQUESTED || state == State.SLEEP_REQUESTED ? State.SLEEP_REQUESTED : State.OUT_OF_ORDER;
                break;
            default: break;
        }
    }
    public synchronized State state() { return state; }
    public synchronized void reset() { state = State.UNKNOWN; }
}
