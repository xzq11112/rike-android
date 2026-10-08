package app.rike.offline;

import android.app.AlertDialog;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28) @LooperMode(LooperMode.Mode.PAUSED)
public class StatisticsSelectionTest {
    private static void set(MainActivity a,String n,Object v)throws Exception{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);f.set(a,v);}
    private static void show(MainActivity a)throws Exception{Method m=MainActivity.class.getDeclaredMethod("showApp");m.setAccessible(true);m.invoke(a);}
    private static <T> List<T> views(View v,Class<T> kind){List<T> r=new ArrayList<>();if(kind.isInstance(v))r.add(kind.cast(v));if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)r.addAll(views(((ViewGroup)v).getChildAt(i),kind));return r;}
    private static void click(View root,String label){View v=TestWork.named(root,label);assertNotNull(label,v);v.performClick();}
    private static void year(MainActivity a,int year){click(a.getWindow().getDecorView(),"选择统计年份");AlertDialog d=ShadowAlertDialog.getLatestAlertDialog();views(d.getWindow().getDecorView(),NumberPicker.class).get(0).setValue(year);d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();}
    @Test public void monthAndYearBrowseIndependentlyWithoutDayActionsOrWrites()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();JSONObject data=Records.empty();
        data.getJSONArray("checkIns").put(new JSONObject().put("id","leap").put("practiceDate","2024-02-29").put("durationMinutes",45));
        VaultCrypto.Created keys=VaultCrypto.create("synthetic-statistics".toCharArray());VaultStore store=new VaultStore(a);store.write(VaultCrypto.encrypt(keys.session,data.toString().getBytes(StandardCharsets.UTF_8)));
        byte[] before=store.read();set(a,"session",keys.session);set(a,"data",data);set(a,"tab",3);show(a);
        assertNotNull(TestWork.named(a.getWindow().getDecorView(),"选择统计年份"));assertNull(TestWork.named(a.getWindow().getDecorView(),"查看某一天"));
        click(a.getWindow().getDecorView(),"选择统计月份");AlertDialog d=ShadowAlertDialog.getLatestAlertDialog();NumberPicker picker=views(d.getWindow().getDecorView(),NumberPicker.class).get(0);assertEquals(1,picker.getMinValue());assertEquals(9999,picker.getMaxValue());picker.setValue(2024);click(d.getWindow().getDecorView(),"2 月");
        assertEquals(YearMonth.of(2024,2),TestWork.get(a,"heatmapMonth"));List<HeatmapView> grids=views(a.getWindow().getDecorView(),HeatmapView.class);assertEquals(2,grids.size());HeatmapView grid=grids.get(0);assertEquals(29,grid.days.size());assertEquals(45,grid.days.get(28).minutes);assertFalse(grid.isClickable());
        android.view.accessibility.AccessibilityNodeInfo node=grid.getAccessibilityNodeProvider().createAccessibilityNodeInfo(28);assertFalse(node.isClickable());assertFalse(grid.getAccessibilityNodeProvider().performAction(28,android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,null));
        click(a.getWindow().getDecorView(),"选择统计月份");d=ShadowAlertDialog.getLatestAlertDialog();views(d.getWindow().getDecorView(),NumberPicker.class).get(0).setValue(9999);click(d.getWindow().getDecorView(),"12 月");assertEquals(LocalDate.of(9999,12,31),views(a.getWindow().getDecorView(),HeatmapView.class).get(0).days.get(30).date);
        click(a.getWindow().getDecorView(),"选择统计月份");d=ShadowAlertDialog.getLatestAlertDialog();views(d.getWindow().getDecorView(),NumberPicker.class).get(0).setValue(1);d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(YearMonth.of(9999,12),TestWork.get(a,"heatmapMonth"));
        year(a,2024);grids=views(a.getWindow().getDecorView(),HeatmapView.class);assertEquals(366,grids.get(1).days.size());assertEquals(45,grids.get(1).days.get(59).minutes);assertFalse(grids.get(1).isClickable());assertEquals(YearMonth.of(9999,12),TestWork.get(a,"heatmapMonth"));
        year(a,1);assertEquals(LocalDate.of(1,1,1),views(a.getWindow().getDecorView(),HeatmapView.class).get(1).days.get(0).date);year(a,9999);assertEquals(LocalDate.of(9999,12,31),views(a.getWindow().getDecorView(),HeatmapView.class).get(1).days.get(364).date);
        TestWork.navigate(a,0);TestWork.navigate(a,3);assertEquals(YearMonth.of(9999,12),TestWork.get(a,"heatmapMonth"));assertEquals(9999,TestWork.get(a,"heatmapYear"));assertArrayEquals(before,store.read());assertEquals(data.toString(),((JSONObject)TestWork.get(a,"data")).toString());c.pause().stop().destroy();
    }
    @Test public void backupAndMaintenanceAreNestedUnderSettings()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();VaultCrypto.Created keys=VaultCrypto.create("synthetic-settings".toCharArray());set(a,"session",keys.session);set(a,"data",Records.empty());show(a);
        assertNull(TestWork.named(a.getWindow().getDecorView(),"更多"));assertTrue(TestWork.named(a.getWindow().getDecorView(),"设置") instanceof Button);
        TestWork.navigate(a,5);View screen=a.getWindow().getDecorView();assertNotNull(TestWork.named(screen,"解锁方式"));assertNotNull(TestWork.named(screen,"备份"));assertNull(TestWork.named(screen,"导出加密备份"));assertNull(TestWork.named(screen,"查看本地诊断"));assertNull(TestWork.named(screen,"版本与更新"));
        assertNotNull(TestWork.named(screen,"维护"));
        click(screen,"备份");screen=a.getWindow().getDecorView();assertNotNull(TestWork.named(screen,"导出加密备份"));assertNull(TestWork.named(screen,"查看本地诊断"));assertNull(TestWork.named(screen,"版本与更新"));click(screen,"返回设置");assertEquals(5,TestWork.get(a,"tab"));
        click(a.getWindow().getDecorView(),"维护");screen=a.getWindow().getDecorView();assertNotNull(TestWork.named(screen,"查看本地诊断"));assertNotNull(TestWork.named(screen,"版本与更新"));assertNull(TestWork.named(screen,"导出加密备份"));
        click(screen,"版本与更新");a.onBackPressed();assertEquals(6,TestWork.get(a,"tab"));assertNull(TestWork.get(a,"maintenance"));a.onBackPressed();assertEquals(5,TestWork.get(a,"tab"));c.pause().stop().destroy();
    }
}
