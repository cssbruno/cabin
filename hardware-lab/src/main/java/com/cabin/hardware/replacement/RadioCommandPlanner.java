package com.cabin.hardware.replacement;

import com.cabin.hardware.FytMcuCodec;

/** MCU radio driver 1 (r0/a) from the pinned Joying firmware, not NativeRadio driver 4.
 * Produces a frame; it never opens or writes a device and never fabricates readback.
 */
public final class RadioCommandPlanner {
    private RadioCommandPlanner() { }
    public static byte[] frame(int driverId, int serviceCommand, int... args) {
        return FytMcuCodec.encode(payload(driverId,serviceCommand,args));
    }
    public static byte[] payload(int driverId,int command,int... args) {
        if (driverId != 1) throw new UnsupportedOperationException("Only MCU radio driver 1 is implemented");
        if (args == null) args = new int[0];
        switch (command) {
            case 0: arity(args,0); return control(0x0a);
            case 1: arity(args,0); return control(0x09);
            case 3: arity(args,0); return control(0x11);
            case 4: arity(args,0); return control(0x10);
            case 5: arity(args,0); return control(0x06);
            case 6: arity(args,0); return control(0x05);
            case 7: case 8:
                arity(args,1); int channel=args[0];
                if (channel >= 0 && channel < 12) return control((command==7 ? 0xe5 : 0x53)+channel);
                if (channel >= 65536 && channel < 65554) return control((command==7 ? 0x81 : 0x41)+channel-65536);
                throw new IllegalArgumentException("Radio channel out of range");
            case 9: arity(args,0); return control(0x12);
            case 10: arity(args,0); return control(0x08);
            case 11:
                arity(args,1); int band=args[0];
                if (band==0 || band==1) return control(0x1d+band);
                if (band>=65536 && band<=65538) return control(0x1a+band-65536);
                if (band==-1) return control(0x18);
                throw new IllegalArgumentException("Explicit band required; relative selection needs verified state");
            case 12:
                arity(args,1); range(args[0],0,4); return control(0x21+args[0]);
            case 13:
                arity(args,2);
                if (args[0]!=3) throw new UnsupportedOperationException("Tuning mode needs verified region range/step state");
                range(args[1],0,10800); return new byte[] {0x25,(byte)(args[1]>>>8),(byte)args[1]};
            case 15:
                arity(args,1); range(args[0],0,1); return new byte[] {1,0,(byte)(0x9e+args[0])};
            case 18:
                arity(args,1); if (args[0]!=2) throw new UnsupportedOperationException("LOC supports toggle only in reference driver"); return control(0x0d);
            case 22:
                arity(args,1); if (args[0]!=2) throw new UnsupportedOperationException("Search supports toggle only in reference driver"); return control(0x04);
            default: throw new UnsupportedOperationException("Radio command not yet verified: "+command);
        }
    }
    private static void arity(int[] args,int count) { if (args.length!=count) throw new IllegalArgumentException("Wrong radio argument count"); }
    private static void range(int value,int min,int max) { if(value<min || value>max) throw new IllegalArgumentException("Radio argument out of range"); }
    private static byte[] control(int opcode) { return new byte[] {1,3,(byte)opcode}; }
}
