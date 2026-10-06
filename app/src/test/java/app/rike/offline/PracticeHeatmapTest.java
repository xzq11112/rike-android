package app.rike.offline;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PracticeHeatmapTest {
    @Test public void leapYearAndMonthsAreContinuousAndKeepActualMinutes()throws Exception {
        JSONArray checks=new JSONArray().put(new JSONObject().put("practiceDate","2024-02-29").put("durationMinutes",20))
            .put(new JSONObject().put("practiceDate","2024-02-29").put("durationMinutes",25));
        String unchanged=checks.toString();Map<String,Long> minutes=PracticeStats.from(checks,LocalDate.of(2024,3,1)).minutesByDate;
        List<PracticeHeatmap.Day> year=PracticeHeatmap.year(2024,minutes);assertEquals(366,year.size());
        for(int i=1;i<year.size();i++)assertEquals(year.get(i-1).date.plusDays(1),year.get(i).date);
        assertEquals(LocalDate.of(2024,1,1),year.get(0).date);assertEquals(LocalDate.of(2024,12,31),year.get(365).date);
        assertEquals(365,PracticeHeatmap.year(2025,minutes).size());
        List<PracticeHeatmap.Day> feb=PracticeHeatmap.month(2024,2,minutes);assertEquals(29,feb.size());
        assertEquals(45,feb.get(28).minutes);assertEquals(0,feb.get(0).minutes);assertEquals(unchanged,checks.toString());
        assertEquals(45,PracticeStats.from(checks,LocalDate.of(2024,3,1)).totalMinutes);
    }
    @Test public void fixedYellowAnchorsInterpolateEveryMinuteWithoutUserNormalization(){
        for(int i=0;i<PracticeHeatmap.ANCHORS.length;i++)assertEquals(PracticeHeatmap.YELLOWS[i],PracticeHeatmap.color(PracticeHeatmap.ANCHORS[i],false));
        java.util.Set<Integer> colors=new java.util.HashSet<>();
        for(int minute=1;minute<=240;minute++){
            int color=PracticeHeatmap.color(minute,false);colors.add(color);
            assertEquals(color,PracticeHeatmap.color(minute,true));
            assertTrue(((color>>16)&255)>=((color>>8)&255));assertTrue(((color>>8)&255)>=(color&255));
        }
        assertTrue(colors.size()>100);assertNotEquals(PracticeHeatmap.color(30,false),PracticeHeatmap.color(31,false));
        assertEquals(PracticeHeatmap.color(240,false),PracticeHeatmap.color(Long.MAX_VALUE,false));
        assertNotEquals(PracticeHeatmap.color(0,false),PracticeHeatmap.color(1,false));assertNotEquals(PracticeHeatmap.color(0,false),PracticeHeatmap.color(0,true));
        // Same absolute duration across very different datasets still yields the same color.
        List<PracticeHeatmap.Day> small=PracticeHeatmap.month(2024,2,Map.of("2024-02-29",45L));
        List<PracticeHeatmap.Day> large=PracticeHeatmap.year(2024,Map.of("2024-02-29",45L,"2024-03-01",10000L));
        assertEquals(PracticeHeatmap.color(small.get(28).minutes,false),PracticeHeatmap.color(large.get(59).minutes,false));
    }
}
