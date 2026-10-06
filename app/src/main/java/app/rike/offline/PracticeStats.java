package app.rike.offline;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Derived from live check-ins only. Never persisted, logged or exported. */
public final class PracticeStats {
    public final long todayMinutes, weekMinutes, totalMinutes;
    public final int totalCount, streak;
    public final Map<String,Long> minutesByDate;

    private PracticeStats(long today,long week,long total,int count,int streak,Map<String,Long> days){
        todayMinutes=today;weekMinutes=week;totalMinutes=total;totalCount=count;this.streak=streak;
        minutesByDate=Collections.unmodifiableMap(days);
    }
    public static PracticeStats from(JSONArray checkIns,LocalDate today)throws JSONException {
        Map<String,Long> days=new HashMap<>();long total=0,week=0;
        LocalDate start=today.minusDays(6);
        for(int i=0;i<checkIns.length();i++){
            JSONObject record=checkIns.getJSONObject(i);String date=record.getString("practiceDate");
            long minutes=record.getInt("durationMinutes");LocalDate day=LocalDate.parse(date);
            days.put(date,days.getOrDefault(date,0L)+minutes);total+=minutes;
            if(!day.isBefore(start)&&!day.isAfter(today))week+=minutes;
        }
        LocalDate cursor=days.containsKey(today.toString())?today:today.minusDays(1);int streak=0;
        while(days.containsKey(cursor.toString())){streak++;cursor=cursor.minusDays(1);}
        return new PracticeStats(days.getOrDefault(today.toString(),0L),week,total,checkIns.length(),streak,days);
    }
}
