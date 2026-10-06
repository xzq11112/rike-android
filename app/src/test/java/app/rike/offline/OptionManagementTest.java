package app.rike.offline;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class OptionManagementTest {
    private static JSONObject type(String id)throws Exception{return new JSONObject().put("id",id).put("name",id);}
    private static JSONObject minutes(String id,int n)throws Exception{return new JSONObject().put("id",id).put("minutes",n);}
    @Test public void durationEditsSortNumericallyAndDeletionKeepsHistoricalMinutes()throws Exception{
        JSONObject d=Records.empty();Records.upsert(d,"durationPreset",minutes("sixty",60));Records.upsert(d,"durationPreset",minutes("ten",10));Records.upsert(d,"durationPreset",minutes("thirty",30));
        assertEquals(10,d.getJSONArray("durationPresets").getJSONObject(0).getInt("minutes"));assertEquals(60,d.getJSONArray("durationPresets").getJSONObject(2).getInt("minutes"));
        d.getJSONObject("drafts").put("checkIn",new JSONObject().put("preset",30));
        Records.upsert(d,"checkIn",new JSONObject().put("id","past").put("practiceDate","2026-10-04").put("practiceTypeName","SYNTHETIC").put("durationMinutes",30));
        Records.upsert(d,"durationPreset",minutes("thirty",5));assertEquals(5,d.getJSONObject("drafts").getJSONObject("checkIn").getInt("preset"));
        assertEquals(5,d.getJSONArray("durationPresets").getJSONObject(0).getInt("minutes"));Records.delete(d,"durationPreset","thirty");
        assertFalse(d.getJSONObject("drafts").getJSONObject("checkIn").has("preset"));assertEquals(30,d.getJSONArray("checkIns").getJSONObject(0).getInt("durationMinutes"));assertEquals(10,Records.validate(d).getJSONArray("durationPresets").getJSONObject(0).getInt("minutes"));
    }
    @Test public void typeOrderSurvivesRenameExportAndRemovalWithoutChangingPastName()throws Exception{
        JSONObject d=Records.empty();Records.upsert(d,"practiceType",type("Alpha"));Records.upsert(d,"practiceType",type("Beta"));Records.upsert(d,"practiceType",type("Gamma"));
        Records.movePracticeType(d,"Gamma",-1);Records.movePracticeType(d,"Gamma",-1);assertEquals("Gamma",d.getJSONArray("practiceTypes").getJSONObject(0).getString("id"));
        Records.upsert(d,"practiceType",type("Gamma").put("name","Renamed"));assertEquals("Gamma",Records.validate(d).getJSONArray("practiceTypes").getJSONObject(0).getString("id"));
        Records.upsert(d,"checkIn",new JSONObject().put("id","past").put("practiceDate","2026-10-04").put("practiceTypeName","Gamma").put("practiceTypeId","Gamma").put("durationMinutes",10));
        d.getJSONObject("drafts").put("checkIn",new JSONObject().put("typeId","Gamma"));Records.delete(d,"practiceType","Gamma");assertFalse(d.getJSONObject("drafts").getJSONObject("checkIn").has("typeId"));assertEquals("Gamma",d.getJSONArray("checkIns").getJSONObject(0).getString("practiceTypeName"));assertEquals("Alpha",d.getJSONArray("practiceTypes").getJSONObject(0).getString("id"));
    }
    @Test public void journalEditsCannotChangeOrInventFirstSavedTime()throws Exception{
        JSONObject d=Records.empty(),j=new JSONObject().put("id","j").put("journalDate","2026-10-04").put("content","SYNTHETIC").put("createdAt","2026-10-04T01:02:03Z");Records.upsert(d,"journal",j);
        Records.upsert(d,"journal",Records.copy(j).put("createdAt","2026-10-04T09:00:00Z").put("updatedAt","2026-10-04T09:00:00Z").put("content","EDITED"));assertEquals("2026-10-04T01:02:03Z",Records.journal(d,"2026-10-04").getString("createdAt"));
        JSONObject legacy=new JSONObject().put("id","old").put("journalDate","2026-10-03").put("content","LEGACY");Records.upsert(d,"journal",legacy);Records.upsert(d,"journal",Records.copy(legacy).put("createdAt",Records.now()).put("content","EDITED"));assertFalse(Records.journal(d,"2026-10-03").has("createdAt"));assertEquals("时间未记录",RecordTime.label(Records.journal(d,"2026-10-03")));
    }
}
