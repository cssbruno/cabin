package com.cabin.hardware.replacement;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Source volume banks from t0/g.v0 and SOUND callback 0x43. No guessed defaults. */
public final class SoundVolumeMemory {
    public interface Store { void save(int[] levels) throws IOException; }
    private final Store store;
    private final int maximum;
    private int[] levels;

    public SoundVolumeMemory(int maximum,int[] restoredLevels,Store store) {
        if(maximum<1 || maximum>255 || restoredLevels==null || restoredLevels.length!=3)
            throw new IllegalArgumentException("Three explicitly restored volume banks required");
        this.maximum=maximum; this.store=Objects.requireNonNull(store);
        levels=restoredLevels.clone();
        for(int value:levels) validate(value);
    }
    /** MAIN app 2 selects call volume; app 15 selects extra-call volume; others select media. */
    public static int bank(int appId) {
        if(appId<0 || appId>255) throw new IllegalArgumentException("Resolved MAIN app required");
        return appId==2 ? 1 : appId==15 ? 2 : 0;
    }
    public synchronized int forSource(int appId) { return levels[bank(appId)]; }
    public int maximum() { return maximum; }
    /** Called only after the owning audio operation succeeds. Persistence failures are surfaced. */
    public synchronized void remember(int appId,int level) throws IOException {
        int bank=bank(appId); validate(level);
        if(levels[bank]==level) return;
        int[] updated=levels.clone(); updated[bank]=level;
        store.save(updated.clone());
        levels=updated;
    }
    public synchronized List<ModulePayload> cached() {
        return List.of(ModulePayload.integers(0,levels[0]),ModulePayload.integers(1,levels[1]),ModulePayload.integers(2,levels[2]));
    }
    private void validate(int value) {
        if(value<0 || value>maximum) throw new IllegalArgumentException("Saved volume outside configured range");
    }
}
