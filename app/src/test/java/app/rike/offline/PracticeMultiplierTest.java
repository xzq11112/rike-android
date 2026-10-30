package app.rike.offline;

import java.time.LocalDate;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class PracticeMultiplierTest {
    private JSONObject check(String id,int minutes,double rate)throws Exception {
        return new JSONObject().put("id",id).put("practiceDate","2026-10-14")
            .put("practiceTypeId","t").put("practiceTypeName","Synthetic")
            .put("durationMinutes",minutes).put("durationMultiplier",rate);
    }
    @Test public void rawTimeIsPreservedAndCreditedMinutesAreTruncated()throws Exception {
        JSONObject r=check("a",15,0.7);Records.validateRecord("checkIn",r);
        assertEquals(15,r.getInt("durationMinutes"));assertEquals(10,Records.effectiveMinutes(r),0);
        assertEquals("15 分钟 × 0.7 = 10 分钟",Records.durationLabel(r));
        assertEquals(42,Records.effectiveMinutes(check("b",60,0.7)),0);
    }
    @Test public void subMinuteCreditsAreDiscardedBeforeAggregation()throws Exception {
        JSONArray rows=new JSONArray();for(int i=0;i<100;i++)rows.put(check("c"+i,1,0.01));
        PracticeStats s=PracticeStats.from(rows,LocalDate.of(2026,10,14));
        assertEquals(0,s.todayMinutes,0);assertEquals(0,s.weekMinutes,0);assertEquals(0,s.totalMinutes,0);
        assertEquals(100,s.totalCount);assertEquals(1,s.streak);
        assertEquals(0,PracticeHeatmap.month(2026,10,s.minutesByDate).get(13).minutes,0);
    }
    @Test public void legacyRecordsDefaultToOne()throws Exception {
        JSONObject r=check("a",15,0.7);r.remove("durationMultiplier");
        Records.validateRecord("checkIn",r);assertEquals(15,Records.effectiveMinutes(r),0);
        assertEquals("15 分钟",Records.durationLabel(r));
    }
    @Test public void typeChangesAndBackupRoundTripPreserveHistoricalRate()throws Exception {
        JSONObject data=Records.empty(),type=new JSONObject().put("id","t").put("name","Synthetic").put("durationMultiplier",0.7);
        Records.upsert(data,"practiceType",type);Records.upsert(data,"checkIn",check("a",60,Records.multiplier(type)));
        type.put("durationMultiplier",0.5);Records.upsert(data,"practiceType",type);
        JSONObject imported=Records.validate(new JSONObject(data.toString()));
        assertEquals(42,Records.effectiveMinutes(Records.find(imported,"checkIn","a")),0);
        JSONObject edited=Records.copy(Records.find(imported,"checkIn","a")).put("durationMinutes",15);
        Records.upsert(imported,"checkIn",edited);assertEquals(10,Records.effectiveMinutes(Records.find(imported,"checkIn","a")),0);
        Records.delete(imported,"practiceType","t");
        assertEquals(10,PracticeStats.from(imported.getJSONArray("checkIns"),LocalDate.of(2026,10,14)).totalMinutes,0);
    }
    @Test public void invalidRatesAreRejected()throws Exception {
        for(String value:new String[]{"0","-1","10.01","0.001","NaN","Infinity","","null"}) {
            try {Records.multiplier(value);fail(value);}catch(IllegalArgumentException expected){}
        }
        assertEquals(0.7,Records.multiplier("0.70"),0);assertEquals(10,Records.multiplier("10"),0);
        JSONObject row=check("a",60,1).put("durationMultiplier",JSONObject.NULL);
        try {Records.validateRecord("checkIn",row);fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void encryptedBackupAndDeletedRecordRestoreKeepCredit()throws Exception {
        JSONObject data=Records.empty();Records.upsert(data,"checkIn",check("a",15,0.7));
        Records.delete(data,"checkIn","a");
        char[] password="synthetic-multiplier-test".toCharArray();
        VaultCrypto.Created keys=VaultCrypto.create(password);
        byte[] backup;
        try {backup=VaultCrypto.encrypt(keys.session,data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        finally {keys.session.close();}
        VaultCrypto.Opened opened=VaultCrypto.open(backup,password);
        try {
            JSONObject restored=Records.validate(new JSONObject(new String(opened.plaintext,java.nio.charset.StandardCharsets.UTF_8)));
            Records.restore(restored,0);
            assertEquals(15,Records.find(restored,"checkIn","a").getInt("durationMinutes"));
            assertEquals(10,PracticeStats.from(restored.getJSONArray("checkIns"),LocalDate.of(2026,10,14)).totalMinutes,0);
        } finally {opened.session.close();java.util.Arrays.fill(opened.plaintext,(byte)0);}
    }
}
