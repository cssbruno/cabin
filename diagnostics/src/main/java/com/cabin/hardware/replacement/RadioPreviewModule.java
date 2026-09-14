package com.cabin.hardware.replacement;

/** Runs real reference command encoding against an offline journal, with no device state. */
public final class RadioPreviewModule implements ModuleEndpoint {
    private final CommandJournal journal;
    private boolean closed;
    public RadioPreviewModule(CommandJournal journal) { this.journal=java.util.Objects.requireNonNull(journal); }
    @Override public synchronized void command(int code,ModulePayload args) {
        if(closed) throw new IllegalStateException("Radio preview closed");
        if(!args.integerOnly()) throw new IllegalArgumentException("Radio command requires integers");
        journal.record(1,code,RadioCommandPlanner.frame(1,code,args.integers()));
    }
    @Override public synchronized void close() { closed=true; }
}
