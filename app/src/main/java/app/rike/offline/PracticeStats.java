package app.rike.offline;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Derived from live check-ins only. Never persisted, logged or exported. */
public final class PracticeStats {
    public final double todayMinutes, weekMinutes, totalMinutes;
    public final long weekAverageMinutes, totalAverageMinutes;
    public final int totalCount, streak;
    public final Map<String,Double> minutesByDate;

    private PracticeStats(long today,long week,long total,int count,int streak,Map<String,Long> days,long elapsedDays){
        todayMinutes=today/100.0;weekMinutes=week/100.0;totalMinutes=total/100.0;totalCount=count;this.streak=streak;
        Map<String,Double> result=new HashMap<>();for(String date:days.keySet())result.put(date,days.get(date)/100.0);
        minutesByDate=Collections.unmodifiableMap(result);
        weekAverageMinutes=Math.round(week/700.0);
        totalAverageMinutes=Math.round(total/(100.0*elapsedDays));
    }
    public static String formatMinutes(double minutes) {
        long hundredths=Math.round(minutes*100);
        return hundredths<6000?Records.decimal(hundredths/100.0)+"分钟":hundredths/6000+"小时"+Records.decimal((hundredths%6000)/100.0)+"分钟";
    }
    public static PracticeStats from(JSONArray checkIns,LocalDate today)throws JSONException {
        Map<String,Long> days=new HashMap<>();long total=0,week=0;
        LocalDate start=today.minusDays(6),firstPracticeDate=today;
        for(int i=0;i<checkIns.length();i++){
            JSONObject record=checkIns.getJSONObject(i);String date=record.getString("practiceDate");
            long minutes=Records.effectiveHundredths(record);LocalDate day=LocalDate.parse(date);
            days.put(date,days.getOrDefault(date,0L)+minutes);total+=minutes;
            if(day.isBefore(firstPracticeDate))firstPracticeDate=day;
            if(!day.isBefore(start)&&!day.isAfter(today))week+=minutes;
        }
        LocalDate cursor=days.containsKey(today.toString())?today:today.minusDays(1);int streak=0;
        while(days.containsKey(cursor.toString())){streak++;cursor=cursor.minusDays(1);}
        return new PracticeStats(days.getOrDefault(today.toString(),0L),week,total,checkIns.length(),streak,days,ChronoUnit.DAYS.between(firstPracticeDate,today)+1);
    }
}
