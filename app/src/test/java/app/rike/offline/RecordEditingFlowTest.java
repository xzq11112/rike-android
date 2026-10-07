package app.rike.offline;

import android.app.AlertDialog;
import android.view.View;
import android.widget.EditText;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class RecordEditingFlowTest {
    private static void set(MainActivity a,String name,Object value)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(a,value);}
    private static void call(MainActivity a,String name)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(a);}
    private static void click(View root,String name){View v=TestWork.named(root,name);assertNotNull(name,v);v.performClick();}
    private static JSONObject data()throws Exception{
        JSONObject data=Records.empty();
        Records.upsert(data,"practiceType",new JSONObject().put("id","type").put("name","SYNTHETIC"));
        Records.upsert(data,"checkIn",new JSONObject().put("id","entry").put("practiceTypeId","type").put("practiceTypeName","SYNTHETIC").put("practiceDate",LocalDate.now().toString()).put("durationMinutes",20).put("note","").put("createdAt","2020-01-01T00:00:00Z"));
        return data;
    }
    private static void open(MainActivity a,JSONObject data)throws Exception{
        VaultCrypto.Created key=VaultCrypto.create("synthetic-edit-password".toCharArray());
        new VaultStore(a).write(VaultCrypto.encrypt(key.session,data.toString().getBytes(StandardCharsets.UTF_8)));
        set(a,"session",key.session);set(a,"data",data);call(a,"showApp");
    }
    private static AlertDialog edit(MainActivity a){TestWork.navigate(a,1);click(a.getWindow().getDecorView(),"编辑这次练习");return ShadowAlertDialog.getLatestAlertDialog();}
    private static List<EditText> fields(AlertDialog d){return TestWork.views(d.getWindow().getDecorView(),EditText.class);}
    @Test public void cancelWithNoChangesClosesImmediately()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();
        try{open(a,data());AlertDialog d=edit(a);d.getButton(-2).performClick();assertFalse(d.isShowing());}finally{c.pause().stop().destroy();}
    }
    @Test public void cancelAndSystemCancelProtectChangesAndKeepOriginalBytes()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();
        try{
            open(a,data());byte[] before=new VaultStore(a).read();AlertDialog d=edit(a);fields(d).get(1).setText("SYNTHETIC-EDIT");
            d.getButton(-2).performClick();AlertDialog confirm=ShadowAlertDialog.getLatestAlertDialog();assertNotSame(d,confirm);assertTrue(d.isShowing());
            confirm.getButton(-2).performClick();assertTrue(d.isShowing());assertEquals("SYNTHETIC-EDIT",fields(d).get(1).getText().toString());
            d.cancel();confirm=ShadowAlertDialog.getLatestAlertDialog();assertNotSame(d,confirm);confirm.getButton(-1).performClick();assertFalse(d.isShowing());assertArrayEquals(before,new VaultStore(a).read());
        }finally{c.pause().stop().destroy();}
    }
    @Test public void revertingChangesDoesNotRequireConfirmation()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();
        try{open(a,data());AlertDialog d=edit(a);fields(d).get(0).setText("30");fields(d).get(0).setText("20");d.cancel();assertFalse(d.isShowing());}finally{c.pause().stop().destroy();}
    }
    @Test public void failedSaveKeepsInputAndCancellationProtectionForRetry()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();
        try{
            open(a,data());VaultStore original=new VaultStore(a);byte[] before=original.read();
            VaultStore failing=new VaultStore(a){@Override public void write(byte[] bytes)throws java.io.IOException{throw new java.io.IOException("synthetic failure");}};
            set(a,"store",failing);AlertDialog d=edit(a);fields(d).get(1).setText("SYNTHETIC-RETRY");d.getButton(-1).performClick();TestWork.drain(a);
            assertTrue(d.isShowing());assertEquals("SYNTHETIC-RETRY",fields(d).get(1).getText().toString());assertArrayEquals(before,original.read());
            d.getButton(-2).performClick();AlertDialog confirm=ShadowAlertDialog.getLatestAlertDialog();assertNotSame(d,confirm);confirm.getButton(-2).performClick();
            set(a,"store",original);d.getButton(-1).performClick();TestWork.drain(a);assertFalse(d.isShowing());
            assertEquals("SYNTHETIC-RETRY",Records.find((JSONObject)TestWork.get(a,"data"),"checkIn","entry").getString("note"));
        }finally{c.pause().stop().destroy();}
    }
    @Test public void editingDateOutsideRecentRangeKeepsSavedRecordVisible()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();
        try{
            open(a,data());AlertDialog d=edit(a);LocalDate target=LocalDate.now().minusDays(40);
            click(d.getWindow().getDecorView(),"练习日期 · "+LocalDate.now());AlertDialog calendar=ShadowAlertDialog.getLatestAlertDialog();TestWork.chooseCalendarMonth(calendar,java.time.YearMonth.from(target));
            click(calendar.getWindow().getDecorView(),"按日期列表选择");click(ShadowAlertDialog.getLatestAlertDialog().getWindow().getDecorView(),target+"，无练习记录");
            fields(d).get(0).setText("35");fields(d).get(1).setText("SYNTHETIC-SAVED");d.getButton(-1).performClick();TestWork.drain(a);
            assertFalse(d.isShowing());assertEquals(target.toString(),TestWork.get(a,"historyDate"));JSONObject row=Records.find((JSONObject)TestWork.get(a,"data"),"checkIn","entry");
            assertEquals(35,row.getInt("durationMinutes"));assertEquals("2020-01-01T00:00:00Z",row.getString("createdAt"));assertNotNull(TestWork.named(a.getWindow().getDecorView(),"SYNTHETIC-SAVED"));
            assertNotNull(TestWork.named(a.getWindow().getDecorView(),"练习修改已保存 · "+target));
        }finally{c.pause().stop().destroy();}
    }
    @Test public void pastJournalHeadingAndBackdatedReceiptUseSelectedDay()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();
        try{
            JSONObject data=data();String day=LocalDate.now().minusDays(10).toString();data.getJSONObject("drafts").put("checkIn",new JSONObject().put("typeId","type").put("manual","25").put("date",day).put("dateMode","manual"));
            open(a,data);click(a.getWindow().getDecorView(),"保存练习");TestWork.drain(a);assertNotNull(TestWork.named(a.getWindow().getDecorView(),"练习已补记 · "+day));
            TestWork.navigate(a,2);set(a,"journalDate",day);set(a,"journalEditing",true);call(a,"showApp");assertNull(TestWork.named(a.getWindow().getDecorView(),"写下今日所得"));assertNotNull(TestWork.named(a.getWindow().getDecorView(),"记录这一天的所得"));
        }finally{c.pause().stop().destroy();}
    }
}

