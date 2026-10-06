package app.rike.offline;

import java.time.LocalDate;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PracticeStatsTest {
    private static JSONObject check(String day,int minutes)throws Exception {
        return new JSONObject().put("practiceDate",day).put("durationMinutes",minutes);
    }
    @Test public void windowIncludesTodayAndSixPriorDaysButNotFuture()throws Exception {
        JSONArray rows=new JSONArray().put(check("2026-09-20",20)).put(check("2026-09-20",30))
            .put(check("2026-09-19",15)).put(check("2026-09-14",10))
            .put(check("2026-09-13",60)).put(check("2026-09-21",5));
        String before=rows.toString();PracticeStats s=PracticeStats.from(rows,LocalDate.parse("2026-09-20"));
        assertEquals(50,s.todayMinutes);assertEquals(75,s.weekMinutes);assertEquals(140,s.totalMinutes);
        assertEquals(6,s.totalCount);assertEquals(2,s.streak);assertEquals(Long.valueOf(50),s.minutesByDate.get("2026-09-20"));
        assertEquals(before,rows.toString());
    }
    @Test public void streakCountsDistinctDaysAndCanStartYesterdayAcrossLeapDay()throws Exception {
        JSONArray rows=new JSONArray().put(check("2024-02-29",10)).put(check("2024-02-29",15)).put(check("2024-02-28",5));
        PracticeStats s=PracticeStats.from(rows,LocalDate.parse("2024-03-01"));assertEquals(2,s.streak);assertEquals(0,s.todayMinutes);
        assertEquals(0,PracticeStats.from(rows,LocalDate.parse("2024-03-02")).streak);
        PracticeStats empty=PracticeStats.from(new JSONArray(),LocalDate.parse("2024-03-01"));
        assertEquals(0,empty.totalCount);assertEquals(0,empty.streak);assertTrue(empty.minutesByDate.isEmpty());
    }
}
