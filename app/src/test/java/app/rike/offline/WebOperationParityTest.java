package app.rike.offline;

import android.app.AlertDialog;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28) @LooperMode(LooperMode.Mode.PAUSED)
public class WebOperationParityTest {
    private static void set(MainActivity a,String n,Object v)throws Exception{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);f.set(a,v);}
    private static void show(MainActivity a)throws Exception{Method m=MainActivity.class.getDeclaredMethod("showApp");m.setAccessible(true);m.invoke(a);}
    private static void open(MainActivity a,JSONObject d)throws Exception{VaultCrypto.Created k=VaultCrypto.create("synthetic-parity".toCharArray());new VaultStore(a).write(VaultCrypto.encrypt(k.session,d.toString().getBytes(StandardCharsets.UTF_8)));set(a,"session",k.session);set(a,"data",d);show(a);}
    private static String text(View v){StringBuilder s=new StringBuilder();if(v instanceof TextView)s.append(((TextView)v).getText()).append('\n');if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)s.append(text(((ViewGroup)v).getChildAt(i)));return s.toString();}
    private static JSONObject check(String id,LocalDate day,int n,String note)throws Exception{return new JSONObject().put("id",id).put("practiceDate",day.toString()).put("practiceTypeName","Retired").put("practiceTypeId","retired").put("durationMinutes",n).put("note",note);}
    @Test public void recordGroupsShowFullNotesDailyTotalsAndOldDateQuery()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();LocalDate today=LocalDate.now();JSONObject d=Records.empty();String note="SYNTHETIC-LONG-NOTE-"+"detail\n".repeat(150);
        Records.upsert(d,"checkIn",check("a",today,20,note));Records.upsert(d,"checkIn",check("b",today,30,"second"));Records.upsert(d,"checkIn",check("six",today.minusDays(6),5,"SYNTHETIC-IN-RANGE"));Records.upsert(d,"checkIn",check("old",today.minusDays(7),7,"SYNTHETIC-OLD"));open(a,d);TestWork.navigate(a,1);View screen=a.getWindow().getDecorView();String visible=text(screen);
        assertTrue(visible.contains(note));assertTrue(visible.contains("2 次练习"));assertTrue(visible.contains("小计 50 分钟"));assertTrue(visible.contains("历史类型"));assertTrue(visible.contains("SYNTHETIC-IN-RANGE"));assertFalse(visible.contains("SYNTHETIC-OLD"));assertEquals(3,TestWork.views(screen,ImageButton.class).stream().filter(v->"删除打卡记录".contentEquals(v.getContentDescription())).count());assertEquals(3,TestWork.views(screen,ImageButton.class).stream().filter(v->"编辑这次练习".contentEquals(v.getContentDescription())).count());assertNull(TestWork.named(screen,"更多"));
        TestWork.named(screen,"选择日期").performClick();AlertDialog calendar=ShadowAlertDialog.getLatestAlertDialog();LocalDate old=today.minusDays(7);TestWork.chooseCalendarMonth(calendar,YearMonth.from(old));TestWork.views(calendar.getWindow().getDecorView(),HeatmapView.class).get(0).getAccessibilityNodeProvider().performAction(old.getDayOfMonth()-1,android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,null);assertTrue(text(a.getWindow().getDecorView()).contains("SYNTHETIC-OLD"));assertFalse(text(a.getWindow().getDecorView()).contains(note));
        TestWork.deleteFirst(a);ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();TestWork.drain(a);assertEquals(3,((JSONObject)TestWork.get(a,"data")).getJSONArray("checkIns").length());c.pause().stop().destroy();
    }
    @Test public void journalExpandsInPlaceAndEditReturnsToSamePageEditor()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();LocalDate day=LocalDate.now().minusDays(1);JSONObject d=Records.empty();String content="SYNTHETIC-JOURNAL\n"+"line\n".repeat(20);Records.upsert(d,"journal",new JSONObject().put("id","j").put("journalDate",day.toString()).put("content",content).put("createdAt","2020-01-02T03:04:05Z"));Records.upsert(d,"journal",new JSONObject().put("id","old").put("journalDate",day.minusDays(6).toString()).put("content","SYNTHETIC-OLD-JOURNAL"));open(a,d);TestWork.navigate(a,2);View root=(View)TestWork.get(a,"root");assertEquals(1,TestWork.views(root,EditText.class).size());assertFalse(text(root).contains("SYNTHETIC-OLD-JOURNAL"));AlertDialog before=ShadowAlertDialog.getLatestAlertDialog();View entry=TestWork.named(root,"展开感悟 "+day);entry.performClick();assertSame(root,TestWork.get(a,"root"));assertSame(before,ShadowAlertDialog.getLatestAlertDialog());assertNotNull(TestWork.named(root,"收起感悟 "+day));entry.performClick();assertNotNull(TestWork.named(root,"展开感悟 "+day));
        TestWork.named(root,"编辑这篇日记").performClick();assertEquals(2,TestWork.get(a,"renderedRoute"));assertEquals(day.toString(),TestWork.get(a,"journalDate"));EditText editor=TestWork.views(a.getWindow().getDecorView(),EditText.class).get(0);assertEquals(content,editor.getText().toString());editor.setText("SYNTHETIC-UPDATED");TestWork.named(a.getWindow().getDecorView(),"保存感悟").performClick();TestWork.drain(a);JSONObject row=Records.journal((JSONObject)TestWork.get(a,"data"),day.toString());assertEquals("j",row.getString("id"));assertEquals("2020-01-02T03:04:05Z",row.getString("createdAt"));assertEquals(2,((JSONObject)TestWork.get(a,"data")).getJSONArray("journals").length());assertEquals(day.toString(),TestWork.get(a,"journalDate"));assertNull(TestWork.get(a,"journalFilter"));assertNotNull(TestWork.named(a.getWindow().getDecorView(),"选择感悟日期"));TestWork.deleteFirst(a);ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();TestWork.drain(a);assertNull(Records.journal((JSONObject)TestWork.get(a,"data"),day.toString()));a.onBackPressed();assertNull(TestWork.get(a,"session"));c.pause().stop().destroy();
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) public void normalPhoneTodayFitsWithFourShortTypesAndSixDurations()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();JSONObject d=Records.empty();for(String name:new String[]{"静坐","站桩","诵读","行走"})Records.upsert(d,"practiceType",new JSONObject().put("id",name).put("name",name));for(int n:new int[]{5,10,15,20,30,60})Records.upsert(d,"durationPreset",new JSONObject().put("id","m"+n).put("minutes",n));open(a,d);TestWork.layout(a);ScrollView scroll=(ScrollView)TestWork.get(a,"contentScroll");float density=a.getResources().getDisplayMetrics().density;int excess=scroll.getChildAt(0).getMeasuredHeight()-scroll.getMeasuredHeight();StringBuilder sizes=new StringBuilder();for(View v:TestWork.views(scroll,View.class))if(v.isShown())sizes.append(v.getClass().getSimpleName()).append(": ").append(v.getMeasuredWidth()/density).append(" x ").append(v.getMeasuredHeight()/density).append(v instanceof TextView?" "+((TextView)v).getText():"").append("\n");assertTrue("scroll excess dp: "+excess/density+"\n"+sizes,excess<=48*density);for(Button b:TestWork.views(scroll,Button.class))if(b.isShown())assertTrue(b.getText().toString(),b.getMeasuredHeight()>=48*density);assertTrue(TestWork.named(scroll,"保存练习").isShown());assertTrue(TestWork.named(scroll,"任意分钟数").isShown());c.pause().stop().destroy();
    }
}
