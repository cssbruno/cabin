package com.cabin.hardware.replacement;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

/** Invokes the platform methods used by the pinned driver. No hidden-API exemption or SYU JNI. */
public final class AndroidAudioSwitch implements C7604SourceRouting.AndroidSwitch {
    private final Object audioManager;
    private final Method voice,audio,wired;
    public AndroidAudioSwitch(Object audioManager) throws IOException {
        this.audioManager=Objects.requireNonNull(audioManager);
        Method voiceMethod=null,audioMethod=null,wiredMethod=null;
        try {
            try { voiceMethod=audioManager.getClass().getMethod("setVoiceSwitch2iis",boolean.class); }
            catch(NoSuchMethodException missing) { wiredMethod=audioManager.getClass().getMethod("setWiredDeviceConnectionState",int.class,int.class,String.class,String.class); }
            if(voiceMethod!=null) audioMethod=audioManager.getClass().getMethod("setAudioSwitch2iis",boolean.class);
        } catch(ReflectiveOperationException | SecurityException ex) { throw new IOException("Required platform audio switch unavailable",ex); }
        voice=voiceMethod; audio=audioMethod; wired=wiredMethod;
    }
    @Override public synchronized void setIis(boolean enabled) throws IOException {
        try {
            if(voice!=null) { voice.invoke(audioManager,enabled); audio.invoke(audioManager,enabled); }
            else wired.invoke(audioManager,8,enabled?0:1,"","");
        } catch(IllegalAccessException | InvocationTargetException | RuntimeException ex) {
            throw new IOException("Platform audio switch failed; route state uncertain",ex);
        }
    }
}
