package com.cabin.hardware.replacement;

import android.content.SharedPreferences;
import java.io.IOException;
import java.util.Objects;

/** Dedicated preferences per verified hardware profile; saves all volume banks together. */
public final class SoundVolumePreferences implements SoundVolumeMemory.Store {
    private final SharedPreferences preferences;
    private final String profile;
    private final int maximum;
    public SoundVolumePreferences(SharedPreferences preferences,String profile,int maximum) {
        this.preferences=Objects.requireNonNull(preferences);
        if(profile==null || profile.isEmpty() || profile.length()>256 || maximum<1 || maximum>255)
            throw new IllegalArgumentException("Explicit profile and volume maximum required");
        this.profile=profile; this.maximum=maximum;
    }
    public synchronized SoundVolumeMemory restore() throws IOException {
        try {
            if(preferences.getInt("version",-1)!=1 || !profile.equals(preferences.getString("profile",null))
                    || preferences.getInt("maximum",-1)!=maximum)
                throw new IOException("No matching saved volume profile");
            int[] values={preferences.getInt("media",-1),preferences.getInt("call",-1),preferences.getInt("extraCall",-1)};
            return new SoundVolumeMemory(maximum,values,this);
        } catch(ClassCastException | IllegalArgumentException ex) { throw new IOException("Invalid saved volume state",ex); }
    }
    /** Provisioning must supply explicit initial values before restore can succeed. */
    @Override public synchronized void save(int[] levels) throws IOException {
        if(levels==null || levels.length!=3) throw new IllegalArgumentException("Three volume banks required");
        int[] values=levels.clone();
        for(int value:values) if(value<0 || value>maximum) throw new IllegalArgumentException("Volume outside profile range");
        if(!preferences.edit().putInt("version",1).putString("profile",profile).putInt("maximum",maximum)
                .putInt("media",values[0]).putInt("call",values[1]).putInt("extraCall",values[2]).commit())
            throw new IOException("Could not persist volume banks");
    }
}
