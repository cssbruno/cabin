package com.cabin.hondacustom;

import java.util.*;

/** OEM MaintenanceInfoActivity.getResetItemArray + arrays 0x7f050154..156.
 * Variants and item availability come from live status, not guessed Civic equipment.
 */
public final class HondaMaintenance {
    private HondaMaintenance(){}
    private static final String[] US={"A: Engine oil","B: Oil & filter","0: Oil & filter","1: Tire rotation","2: Air filters","3: Transmission oil","4: Spark plugs","5: Engine coolant","6: All wheel drive oil","7: Brake fluid","","9: Brake service and chassis inspections"};
    private static final String[] EU={"A: Oil & filter","","0: Brakes inspection","1: Tire rotation","2: Dust and pollen filter","3: Transmission oil","4: Spark plugs","5: Engine coolant","6: All wheel drive oil","7: Brake fluid","8: Air cleaner element"};
    private static final String[] TURBO={"A: Oil","B: Oil & filter"};
    public static Map<Integer,String> options(int[] status){
        Map<Integer,String> result=new LinkedHashMap<>();
        if(status==null||status.length<3)return result;
        String[] labels=status[2]==1?US:(status[2]==2||status[2]==6)?EU:status[2]==7?TURBO:null;
        if(labels==null||status.length<22+labels.length)return result;
        boolean due=false;
        for(int i=0;i<labels.length;i++)if(!labels[i].isEmpty()){
            if(status[6+i]==1)due=true;
            if(status[22+i]==1)result.put(i==0?10:i==1?11:i-2,labels[i]);
        }
        if(due)result.put(-1,"All due maintenance items");
        return result;
    }
}
