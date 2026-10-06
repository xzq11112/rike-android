package app.rike.offline;

import org.json.*;
import java.time.LocalDate;
import java.util.*;

/** Immutable display index prepared on the worker, reused across draft saves. */
final class RecordIndex {
    final long revision;
    final int checks,journals,deleted;
    final LocalDate day;
    final PracticeStats stats;
    final List<JSONObject> history,journalRows,today,deletedRows;
    final List<JSONObject> recentChecks,recentJournals;
    final Map<String,List<JSONObject>> checksByDay,journalsByDay;
    final Map<String,Long> journalDays;
    RecordIndex(JSONObject data,LocalDate day)throws Exception{
        this.day=day;revision=data.optLong("revision");checks=data.getJSONArray("checkIns").length();journals=data.getJSONArray("journals").length();deleted=data.getJSONArray("deletedRecords").length();
        stats=PracticeStats.from(data.getJSONArray("checkIns"),day);history=sort(data.getJSONArray("checkIns"),"practiceDate");journalRows=sort(data.getJSONArray("journals"),"journalDate");
        List<JSONObject> t=new ArrayList<>(),gone=new ArrayList<>();JSONArray a=data.getJSONArray("checkIns");for(int i=a.length()-1;i>=0;i--){JSONObject r=a.getJSONObject(i);if(day.toString().equals(r.getString("practiceDate")))t.add(r);}
        a=data.getJSONArray("deletedRecords");for(int i=a.length()-1;i>=0;i--){JSONObject r=a.getJSONObject(i);if(!"dream".equals(r.optString("entity")))gone.add(r);}
        today=Collections.unmodifiableList(t);deletedRows=Collections.unmodifiableList(gone);
        checksByDay=group(history,"practiceDate");journalsByDay=group(journalRows,"journalDate");
        recentChecks=recent(history,"practiceDate",day);recentJournals=recent(journalRows,"journalDate",day);
        Map<String,Long> markers=new HashMap<>();for(String date:journalsByDay.keySet())markers.put(date,1L);journalDays=Collections.unmodifiableMap(markers);
    }
    boolean matches(JSONObject data,LocalDate day)throws JSONException{return this.day.equals(day)&&revision==data.optLong("revision")&&checks==data.getJSONArray("checkIns").length()&&journals==data.getJSONArray("journals").length()&&deleted==data.getJSONArray("deletedRecords").length();}
    private static List<JSONObject> sort(JSONArray a,String key)throws Exception{
        List<JSONObject> r=new ArrayList<>();for(int i=a.length()-1;i>=0;i--)r.add(a.getJSONObject(i));
        r.sort((x,y)->{int dates=y.optString(key).compareTo(x.optString(key));if(dates!=0)return dates;java.time.Instant tx=RecordTime.created(x),ty=RecordTime.created(y);if(tx!=null&&ty!=null)return ty.compareTo(tx);if(tx!=null)return -1;if(ty!=null)return 1;return 0;});
        return Collections.unmodifiableList(r);
    }
    private static Map<String,List<JSONObject>> group(List<JSONObject> rows,String key){
        Map<String,List<JSONObject>> grouped=new HashMap<>();for(JSONObject row:rows)grouped.computeIfAbsent(row.optString(key),x->new ArrayList<>()).add(row);
        for(String day:grouped.keySet())grouped.put(day,Collections.unmodifiableList(grouped.get(day)));return Collections.unmodifiableMap(grouped);
    }
    private static List<JSONObject> recent(List<JSONObject> rows,String key,LocalDate today){
        String first=today.minusDays(6).toString(),last=today.toString();List<JSONObject> result=new ArrayList<>();for(JSONObject row:rows){String date=row.optString(key);if(date.compareTo(first)>=0&&date.compareTo(last)<=0)result.add(row);}return Collections.unmodifiableList(result);
    }
}
