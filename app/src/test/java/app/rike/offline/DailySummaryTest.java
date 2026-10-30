package app.rike.offline;

import java.time.LocalDate;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DailySummaryTest {
    static JSONObject record(String name,int minutes,double rate)throws Exception {
        return new JSONObject().put("practiceTypeName",name).put("durationMinutes",minutes)
            .put("durationMultiplier",rate).put("practiceDate","2026-10-29");
    }
    @Test public void displayedCreditsSubtotalAndExportAgree()throws Exception {
        JSONObject a=record("阴性写作",31,0.7),b=record("阴性写作",31,0.7);
        assertEquals("31 分钟 × 0.7 = 21 分钟",Records.durationLabel(a));
        assertEquals("阴性写作 42分钟",Records.dailySummary(Arrays.asList(a,b)));
        PracticeStats stats=PracticeStats.from(new JSONArray().put(a).put(b),LocalDate.of(2026,10,29));
        assertEquals(42,stats.todayMinutes,0);assertEquals(42,stats.totalMinutes,0);
        assertEquals(42,stats.minutesByDate.get("2026-10-29"),0);
        assertEquals(31,a.getInt("durationMinutes"));assertEquals(0.7,Records.multiplier(a),0);
    }
    @Test public void multipleProjectsMergeAndUseHoursWithoutTrailingNewline()throws Exception {
        assertEquals("阴性写作 1小时30分钟\n静坐 2小时\n散步 20分钟",Records.dailySummary(Arrays.asList(
            record("阴性写作",40,1),record("静坐",120,1),record("阴性写作",50,1),record("散步",20,1))));
    }
    @Test public void decimalBoundaryAndSmallCreditsNeverRoundUp()throws Exception {
        assertEquals(29,Records.effectiveMinutes(record("A",100,0.29)),0);
        assertEquals(21,Records.effectiveMinutes(record("A",31,0.7)),0);
        assertEquals("A 0分钟",Records.dailySummary(Arrays.asList(record("A",1,0.7),record("A",1,0.7))));
        assertEquals("",Records.dailySummary(Collections.emptyList()));
    }
    @Test public void legacyNamesAndRatesSurviveBackupRoundTrip()throws Exception {
        JSONObject original=record("已删除项目",61,1);original.remove("durationMultiplier");
        JSONObject imported=new JSONObject(original.toString());
        assertEquals("已删除项目 1小时1分钟",Records.dailySummary(Arrays.asList(imported)));
    }
}
