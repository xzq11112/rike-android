package app.rike.offline;
import org.json.*;
import org.junit.Test;
import java.time.*;
import static org.junit.Assert.*;

public class TimelineTest {
    private JSONObject check(String id,LocalDate day,String time)throws Exception{JSONObject r=new JSONObject().put("id",id).put("practiceDate",day.toString()).put("practiceTypeName","SYNTHETIC").put("durationMinutes",10);if(time!=null)r.put("createdAt",time);return r;}
    @Test public void recentWindowUsesSevenLocalDatesAndDateDescendingBeforeFirstSavedTime()throws Exception{
        LocalDate day=LocalDate.of(2026,10,4);JSONObject d=Records.empty();JSONArray rows=d.getJSONArray("checkIns");
        rows.put(check("outside",day.minusDays(7),"2026-10-04T12:00:00Z"));rows.put(check("future",day.plusDays(1),"2026-10-04T11:00:00Z"));rows.put(check("older",day,"2026-10-04T01:00:00Z"));rows.put(check("newer",day,"2026-10-04T02:00:00Z"));rows.put(check("backfilled",day.minusDays(6),"2026-10-04T03:00:00Z"));
        d.getJSONArray("journals").put(new JSONObject().put("id","old-save").put("journalDate",day.toString()).put("content","SYNTHETIC").put("createdAt","2026-10-04T01:00:00Z").put("updatedAt","2026-10-04T10:00:00Z"));
        d.getJSONArray("journals").put(new JSONObject().put("id","new-save").put("journalDate",day.minusDays(1).toString()).put("content","SYNTHETIC").put("createdAt","2026-10-04T02:00:00Z"));
        RecordIndex index=new RecordIndex(d,day);assertEquals(3,index.recentChecks.size());assertEquals("newer",index.recentChecks.get(0).getString("id"));assertEquals("backfilled",index.recentChecks.get(2).getString("id"));assertEquals("newer",index.checksByDay.get(day.toString()).get(0).getString("id"));assertEquals("old-save",index.recentJournals.get(0).getString("id"));assertEquals(1,index.checksByDay.get(day.minusDays(7).toString()).size());assertFalse(index.journalDays.containsKey(day.minusDays(7).toString()));
    }
    @Test public void legacyTimeFormatsAreReadWithoutInventingMissingValues()throws Exception{
        JSONObject numeric=new JSONObject().put("createdAt",Instant.parse("2026-10-04T01:02:03Z").toEpochMilli()),offset=new JSONObject().put("createdAt","2026-10-04T09:02:03+08:00");assertEquals(RecordTime.created(numeric),RecordTime.created(offset));assertTrue(RecordTime.label(numeric).matches("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}"));assertNull(RecordTime.created(new JSONObject().put("createdAt","invalid")));assertEquals("时间未记录",RecordTime.label(new JSONObject().put("updatedAt",Records.now())));
    }
}
