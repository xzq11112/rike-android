package app.rike.offline;
import android.app.AlertDialog;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import java.lang.reflect.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28) @LooperMode(LooperMode.Mode.PAUSED)
public class SectionLayoutTest {
    private static void set(MainActivity a,String n,Object value)throws Exception{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);f.set(a,value);}
    private static void show(MainActivity a)throws Exception{Method m=MainActivity.class.getDeclaredMethod("showApp");m.setAccessible(true);m.invoke(a);}
    private static void open(MainActivity a,JSONObject data)throws Exception{VaultCrypto.Created c=VaultCrypto.create("012345".toCharArray());new VaultStore(a).write(VaultCrypto.encrypt(c.session,data.toString().getBytes(StandardCharsets.UTF_8)));set(a,"session",c.session);set(a,"data",data);show(a);}
    private static String text(View v){StringBuilder s=new StringBuilder();if(v instanceof TextView)s.append(((TextView)v).getText()).append('\n');if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)s.append(text(((ViewGroup)v).getChildAt(i)));return s.toString();}
    private static EditText input(View v){if(v instanceof EditText)return (EditText)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){EditText e=input(((ViewGroup)v).getChildAt(i));if(e!=null)return e;}return null;}
    private static HeatmapView grid(View v){if(v instanceof HeatmapView)return (HeatmapView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){HeatmapView g=grid(((ViewGroup)v).getChildAt(i));if(g!=null)return g;}return null;}
    private static java.util.List<String> durationButtons(View v){java.util.List<String> labels=new java.util.ArrayList<>();if(v instanceof Button&&((Button)v).getText().toString().matches("[0-9]+ 分钟"))labels.add(((Button)v).getText().toString());if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)labels.addAll(durationButtons(((ViewGroup)v).getChildAt(i)));return labels;}
    private static void click(MainActivity a,String label){assertNotNull(label,TestWork.named(a.getWindow().getDecorView(),label));TestWork.named(a.getWindow().getDecorView(),label).performClick();}
    private static void confirm(MainActivity a)throws Exception{ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();TestWork.drain(a);}
    private static JSONObject check(String id,LocalDate day,String note)throws Exception{return new JSONObject().put("id",id).put("practiceDate",day.toString()).put("practiceTypeName","Beta").put("practiceTypeId","Beta").put("durationMinutes",30).put("note",note).put("createdAt",Instant.now().toString());}
    @Test public void todayShowsStatisticsAndRecordsWhileHeatmapStaysOnDataPage()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();JSONObject d=Records.empty();d.getJSONArray("checkIns").put(check("c",LocalDate.now(),"SYNTHETIC-HISTORY"));open(a,d);
        String today=text(a.getWindow().getDecorView());assertFalse(today.contains("今日已记"));assertFalse(today.contains("练习热力图"));assertFalse(today.contains("SYNTHETIC-HISTORY"));TestWork.navigate(a,1);assertTrue(text(a.getWindow().getDecorView()).contains("SYNTHETIC-HISTORY"));TestWork.navigate(a,0);assertNull(grid(a.getWindow().getDecorView()));
        assertTrue(TestWork.named(a.getWindow().getDecorView(),"切换夜间") instanceof ImageButton);assertTrue(TestWork.named(a.getWindow().getDecorView(),"锁定") instanceof ImageButton);
        TestWork.navigate(a,3);assertNotNull(grid(a.getWindow().getDecorView()));assertTrue(text(a.getWindow().getDecorView()).contains("全年"));assertFalse(grid(a.getWindow().getDecorView()).isClickable());TestWork.navigate(a,5);String settings=text(a.getWindow().getDecorView());assertFalse(settings.contains("练习类型"));assertFalse(settings.contains("常用时长"));assertTrue(settings.contains("更换主密码"));assertTrue(settings.contains("备份"));assertFalse(settings.contains("版本与更新"));assertNull(TestWork.named(a.getWindow().getDecorView(),"导出加密备份"));click(a,"锁定");assertNull(TestWork.get(a,"data"));c.pause().stop().destroy();
    }
    @Test public void inlineDragAndCornerDeletionKeepHistoricalNamesAndMinutes()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();JSONObject d=Records.empty();
        Records.upsert(d,"practiceType",new JSONObject().put("id","Alpha").put("name","Alpha"));Records.upsert(d,"practiceType",new JSONObject().put("id","Beta").put("name","Beta"));d.getJSONArray("checkIns").put(check("past",LocalDate.now(),"SYNTHETIC"));
        for(int n:new int[]{60,10,30})d.getJSONArray("durationPresets").put(new JSONObject().put("id","m"+n).put("minutes",n));open(a,d);assertEquals(java.util.Arrays.asList("10 分钟","30 分钟","60 分钟"),durationButtons(a.getWindow().getDecorView()));
        click(a,"Beta");TestWork.drag(a,"Beta","Alpha",true);assertEquals("Beta",((JSONObject)TestWork.get(a,"data")).getJSONArray("practiceTypes").getJSONObject(0).getString("id"));click(a,"删除Beta");confirm(a);assertEquals("",TestWork.draft(a,"checkIn").optString("typeId"));assertEquals("Beta",((JSONObject)TestWork.get(a,"data")).getJSONArray("checkIns").getJSONObject(0).getString("practiceTypeName"));click(a,"完成练习类型编辑");
        click(a,"30 分钟");TestWork.named(a.getWindow().getDecorView(),"30 分钟").performLongClick();assertEquals("durationPreset",TestWork.get(a,"managedEntity"));click(a,"删除30 分钟");confirm(a);assertFalse(TestWork.draft(a,"checkIn").has("preset"));assertEquals(30,((JSONObject)TestWork.get(a,"data")).getJSONArray("checkIns").getJSONObject(0).getInt("durationMinutes"));assertEquals(java.util.Arrays.asList("10 分钟","60 分钟"),durationButtons(a.getWindow().getDecorView()));c.pause().stop().destroy();
    }
    private static void choose(MainActivity a,LocalDate day,boolean expectMark)throws Exception{
        click(a,"选择日期");AlertDialog d=ShadowAlertDialog.getLatestAlertDialog();TestWork.chooseCalendarMonth(d,YearMonth.from(day));HeatmapView g=grid(d.getWindow().getDecorView());assertNotNull(g);assertEquals(expectMark,g.days.get(day.getDayOfMonth()-1).minutes>0);String label=g.getAccessibilityNodeProvider().createAccessibilityNodeInfo(day.getDayOfMonth()-1).getContentDescription().toString();assertTrue(label.contains(expectMark?"，有":"，无"));assertFalse(label.contains("分钟"));assertTrue(g.getAccessibilityNodeProvider().performAction(day.getDayOfMonth()-1,android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,null));
    }
    @Test public void calendarsFilterAnyDateAndMarkOnlyTheMatchingRecordKind()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();JSONObject d=Records.empty();LocalDate today=LocalDate.now(),past=today.minusDays(30),journalDay=past.minusDays(1);
        d.getJSONArray("checkIns").put(check("now",today,"SYNTHETIC-RECENT")).put(check("old",past,"SYNTHETIC-OLD"));d.getJSONArray("journals").put(new JSONObject().put("id","j").put("journalDate",journalDay.toString()).put("content","SYNTHETIC-OLD-REFLECTION"));open(a,d);
        TestWork.navigate(a,1);String screen=text(a.getWindow().getDecorView());assertTrue(screen.contains("SYNTHETIC-RECENT"));assertFalse(screen.contains("SYNTHETIC-OLD"));choose(a,past,true);screen=text(a.getWindow().getDecorView());assertTrue(screen.contains("SYNTHETIC-OLD"));assertFalse(screen.contains("SYNTHETIC-RECENT"));click(a,"最近 7 天");assertTrue(text(a.getWindow().getDecorView()).contains("SYNTHETIC-RECENT"));
        TestWork.navigate(a,2);assertFalse(text(a.getWindow().getDecorView()).contains("SYNTHETIC-OLD-REFLECTION"));choose(a,past,false);assertTrue(text(a.getWindow().getDecorView()).contains("这段时间尚无感悟"));choose(a,journalDay,true);assertTrue(text(a.getWindow().getDecorView()).contains("SYNTHETIC-OLD-REFLECTION"));assertNotNull(TestWork.named(a.getWindow().getDecorView(),"展开感悟 "+journalDay));c.pause().stop().destroy();
    }
}
