package com.cabin.hardware.replacement;

/** SOUND command 0 semantics from t0/i.cmd and t0/g.w0 in the pinned reference APK.
 * Resolution requires current service state; it performs no hardware I/O.
 */
public final class SoundVolumeCommand {
    public final Integer level;
    public final Boolean muted;
    private SoundVolumeCommand(Integer level,Boolean muted) { this.level=level; this.muted=muted; }

    /** muteAllowed is the resolved call/MAIN policy, not inferred from volume or source. */
    public static SoundVolumeCommand resolve(int request,int current,int maximum,int step,
            boolean muted,boolean muteAllowed) {
        if(maximum<1 || maximum>255 || current<0 || current>maximum || step<1 || step>maximum)
            throw new IllegalArgumentException("Invalid current volume state");
        if(request>=0) return new SoundVolumeCommand(Math.min(request,maximum),muted ? Boolean.FALSE : null);
        switch(request) {
            case -1:
                return current==maximum ? new SoundVolumeCommand(null,null)
                    : new SoundVolumeCommand(Math.min(maximum,current+step),muted ? Boolean.FALSE : null);
            case -2:
                return current==0 ? new SoundVolumeCommand(null,null)
                    : new SoundVolumeCommand(Math.max(0,current-step),muted ? Boolean.FALSE : null);
            case -3:
                return new SoundVolumeCommand(null,muted ? null : muteAllowed);
            case -4:
                return new SoundVolumeCommand(null,muted ? Boolean.FALSE : null);
            case -5:
                return new SoundVolumeCommand(null,!muted && muteAllowed);
            default:
                throw new UnsupportedOperationException("Volume action requires additional lifecycle policy: "+request);
        }
    }
}
