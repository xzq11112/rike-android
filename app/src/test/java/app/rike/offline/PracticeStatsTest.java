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
    @Test public void averagesIncludeIdleCalendarDaysAndRoundToMinutes()throws Exception {
        JSONArray rows=new JSONArray().put(check("2026-10-01",60)).put(check("2026-10-04",120))
            .put(check("2026-10-10",30)).put(check("2026-10-10",90));
        String before=rows.toString();
        PracticeStats s=PracticeStats.from(rows,LocalDate.parse("2026-10-10"));
        assertEquals(300,s.totalMinutes);assertEquals(30,s.totalAverageMinutes);
        assertEquals(240,s.weekMinutes);assertEquals(34,s.weekAverageMinutes);
        assertEquals(before,rows.toString());
        PracticeStats empty=PracticeStats.from(new JSONArray(),LocalDate.parse("2026-10-10"));
        assertEquals(0,empty.totalAverageMinutes);assertEquals(0,empty.weekAverageMinutes);
        PracticeStats first=PracticeStats.from(new JSONArray().put(check("2026-10-10",90)),LocalDate.parse("2026-10-10"));
        assertEquals(90,first.totalAverageMinutes);assertEquals(13,first.weekAverageMinutes);
        assertEquals(30,PracticeStats.from(new JSONArray().put(check("2024-02-28",90)),LocalDate.parse("2024-03-01")).totalAverageMinutes);
        assertEquals(40,PracticeStats.from(new JSONArray().put(check("2025-12-31",120)),LocalDate.parse("2026-01-02")).totalAverageMinutes);
        assertEquals(60,PracticeStats.from(new JSONArray().put(check("2026-03-07",180)),LocalDate.parse("2026-03-09")).totalAverageMinutes);
        assertEquals(60,PracticeStats.from(new JSONArray().put(check("2026-10-09",119)),LocalDate.parse("2026-10-10")).totalAverageMinutes);
        rows.remove(0);
        assertEquals(34,PracticeStats.from(rows,LocalDate.parse("2026-10-10")).totalAverageMinutes);
    }
    @Test public void durationUsesHoursAndMinutes() {
        assertEquals("0分钟",PracticeStats.formatMinutes(0));
        assertEquals("45分钟",PracticeStats.formatMinutes(45));
        assertEquals("1小时0分钟",PracticeStats.formatMinutes(60));
        assertEquals("1小时30分钟",PracticeStats.formatMinutes(90));
        assertEquals("100小时5分钟",PracticeStats.formatMinutes(6005));
    }
}
