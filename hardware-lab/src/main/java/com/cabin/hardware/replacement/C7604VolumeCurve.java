package com.cabin.hardware.replacement;

/** AudioDevice.J/C/k/n with C7604.y = -700; the actual volume table is supplied by the target profile. */
public final class C7604VolumeCurve {
    private final int[] table;
    public C7604VolumeCurve(int[] tableTenthsDb) {
        if(tableTenthsDb==null || tableTenthsDb.length<3 || tableTenthsDb.length>256)
            throw new IllegalArgumentException("Explicit volume table of 3..256 levels required");
        for(int value:tableTenthsDb) if(value < -32768 || value > 32767) throw new IllegalArgumentException("Invalid volume table value");
        table=tableTenthsDb.clone();
    }
    public int levels() { return table.length; }
    public double decibels(int level,int tableOffset,int compensationSteps,int compensationLevel) {
        if(level<0 || level>=table.length || tableOffset < -32768 || tableOffset > 32767
                || compensationSteps<0 || compensationSteps>=table.length || compensationLevel<0 || compensationLevel>255)
            throw new IllegalArgumentException("Invalid volume calibration/state");
        int value=table[level];
        if(level!=0) {
            int direction=table[1]>table[2] ? -1 : 1;
            value -= direction*(Math.abs(table[1]+700)-tableOffset);
            int higher=Math.min(table.length-1,level+compensationSteps);
            if(compensationLevel>60 && higher>level) {
                int delta=table[higher]-table[level];
                value += compensationLevel>=120 ? delta : (int)((delta*(compensationLevel-60))/60.0f+0.5f);
            }
        }
        // Preserve the firmware float division before widening to double.
        return (double)(value/10.0f);
    }
}
