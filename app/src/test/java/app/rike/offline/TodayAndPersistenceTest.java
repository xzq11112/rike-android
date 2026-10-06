package app.rike.offline;

import android.app.AlertDialog;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28) @LooperMode(LooperMode.Mode.PAUSED)
public class TodayAndPersistenceTest {
    private static final char[] PASSWORD="synthetic-persistence-password".toCharArray();
    private static Object get(Object a,String name)throws Exception{Field f=a.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(a);}
    private static void set(Object a,String name,Object value)throws Exception{Field f=a.getClass().getDeclaredField(name);f.setAccessible(true);f.set(a,value);}
    private static void call(Object a,String name)throws Exception{Method m=a.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(a);}
    private static Button button(View v,String label){if(v instanceof Button&&(label.contentEquals(((Button)v).getText())||label.contentEquals(v.getContentDescription()==null?"":v.getContentDescription())))return (Button)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){Button b=button(((ViewGroup)v).getChildAt(i),label);if(b!=null)return b;}return null;}
    private static int buttons(View v,String label){int n=v instanceof Button&&(label.contentEquals(((Button)v).getText())||label.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))?1:0;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)n+=buttons(((ViewGroup)v).getChildAt(i),label);return n;}
    private static String text(View v){StringBuilder b=new StringBuilder();if(v instanceof TextView)b.append(((TextView)v).getText()).append('\n');if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)b.append(text(((ViewGroup)v).getChildAt(i)));return b.toString();}
    private static class FailingStore extends VaultStore {
        boolean failWrites;
        FailingStore(MainActivity a){super(a);}
        @Override public void write(byte[] ciphertext)throws IOException {
            if(failWrites)throw new IOException("SYNTHETIC-private-storage-path");
            super.write(ciphertext);
        }
    }
    private static FailingStore open(MainActivity a,JSONObject d)throws Exception {
        VaultCrypto.Created keys=VaultCrypto.create(PASSWORD);FailingStore store=new FailingStore(a);
        store.write(VaultCrypto.encrypt(keys.session,d.toString().getBytes(StandardCharsets.UTF_8)));
        set(a,"store",store);set(a,"session",keys.session);set(a,"data",d);call(a,"showApp");return store;
    }
    private static JSONObject check(String id,LocalDate day,int minutes,String note)throws Exception {
        return new JSONObject().put("id",id).put("practiceDate",day.toString()).put("durationMinutes",minutes)
            .put("practiceTypeName","历史名称").put("note",note);
    }
    @Test public void todayListAndConfirmedDeletionRefreshStatsAndPreserveHistory()throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();
        JSONObject d=Records.empty();LocalDate now=LocalDate.now();
        Records.upsert(d,"checkIn",check("one",now,20,"SYNTHETIC-TODAY-ONE"));
        Records.upsert(d,"checkIn",check("two",now,30,"SYNTHETIC-TODAY-TWO"));
        Records.upsert(d,"checkIn",check("yesterday",now.minusDays(1),15,"SYNTHETIC-YESTERDAY"));
        FailingStore store=open(a,d);String screen=text(a.getWindow().getDecorView());
        assertTrue(screen.contains("50 分钟"));assertFalse(screen.contains("今日已记"));assertFalse(screen.contains("SYNTHETIC-TODAY"));TestWork.navigate(a,1);assertTrue(text(a.getWindow().getDecorView()).contains("SYNTHETIC-TODAY"));TestWork.navigate(a,0);
        TestWork.navigate(a,3);screen=text(a.getWindow().getDecorView());assertTrue(screen.contains("累计 65 分钟 · 3 次"));assertTrue(screen.contains("连续 2 天"));
        TestWork.navigate(a,1);screen=text(a.getWindow().getDecorView());
        assertTrue(screen.contains("SYNTHETIC-TODAY-ONE"));assertTrue(screen.contains("SYNTHETIC-TODAY-TWO"));assertTrue(screen.contains("SYNTHETIC-YESTERDAY"));
        byte[] before=store.read();TestWork.deleteFirst(a);
        assertArrayEquals(before,store.read());
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();TestWork.drain(a);
        screen=text(a.getWindow().getDecorView());assertTrue(screen.contains("SYNTHETIC-TODAY-ONE"));assertFalse(screen.contains("SYNTHETIC-TODAY-TWO"));
        TestWork.navigate(a,3);screen=text(a.getWindow().getDecorView());assertTrue(screen.contains("累计 35 分钟 · 2 次"));TestWork.navigate(a,0);assertTrue(text(a.getWindow().getDecorView()).contains("20 分钟"));
        JSONObject saved=(JSONObject)get(a,"data");assertEquals("two",saved.getJSONArray("deletedRecords").getJSONObject(0).getJSONObject("record").getString("id"));
        assertEquals(4,saved.getJSONArray("auditLog").length());
        VaultCrypto.Opened reopened=VaultCrypto.open(store.read(),PASSWORD);
        JSONObject disk=new JSONObject(new String(reopened.plaintext,StandardCharsets.UTF_8));assertEquals(saved.toString(),disk.toString());
        Arrays.fill(reopened.plaintext,(byte)0);reopened.session.close();c.pause().stop().destroy();
    }
    @Test public void duplicateImportedDurationsRenderOnceWithoutDeletingEitherRecord()throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();JSONObject d=Records.empty();
        d.getJSONArray("durationPresets").put(new JSONObject().put("id","a").put("minutes",30).put("label","旧名称"))
            .put(new JSONObject().put("id","b").put("minutes",30).put("label","另一个名称"));
        open(a,d);assertEquals(1,buttons(a.getWindow().getDecorView(),"30 分钟"));
        button(a.getWindow().getDecorView(),"30 分钟").performClick();TestWork.flush(a);
        JSONObject saved=(JSONObject)get(a,"data");assertEquals(2,saved.getJSONArray("durationPresets").length());assertEquals(30,saved.getJSONObject("drafts").getJSONObject("checkIn").getInt("preset"));
        button(a.getWindow().getDecorView(),"30 分钟").performClick();TestWork.flush(a);assertFalse(((JSONObject)get(a,"data")).getJSONObject("drafts").getJSONObject("checkIn").has("preset"));
        c.pause().stop().destroy();
    }
    @Test public void failedDraftSaveKeepsCommittedMemoryAndCiphertextThenRetrySucceeds()throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();JSONObject d=Records.empty();
        d.getJSONArray("practiceTypes").put(new JSONObject().put("id","old").put("name","OLD-TYPE"))
            .put(new JSONObject().put("id","new").put("name","NEW-TYPE"));
        d.getJSONObject("drafts").put("checkIn",new JSONObject().put("typeId","old").put("note","SYNTHETIC-EXISTING-NOTE"));
        FailingStore store=open(a,d);String committed=d.toString();byte[] ciphertext=store.read();store.failWrites=true;
        button(a.getWindow().getDecorView(),"NEW-TYPE").performClick();TestWork.flush(a);
        assertEquals(committed,((JSONObject)get(a,"data")).toString());assertArrayEquals(ciphertext,store.read());
        String report=((Diagnostics)get(a,"diagnostics")).report();assertTrue(report.contains("VAULT_WRITE_FAILED"));assertFalse(report.contains("SYNTHETIC"));
        store.failWrites=false;button(a.getWindow().getDecorView(),"NEW-TYPE").performClick();TestWork.flush(a);
        JSONObject saved=((JSONObject)get(a,"data")).getJSONObject("drafts").getJSONObject("checkIn");assertEquals("new",saved.getString("typeId"));assertEquals("SYNTHETIC-EXISTING-NOTE",saved.getString("note"));
        VaultCrypto.Opened reopened=VaultCrypto.open(store.read(),PASSWORD);JSONObject disk=new JSONObject(new String(reopened.plaintext,StandardCharsets.UTF_8));assertEquals("new",disk.getJSONObject("drafts").getJSONObject("checkIn").getString("typeId"));
        Arrays.fill(reopened.plaintext,(byte)0);reopened.session.close();c.pause().stop().destroy();
    }
    @Test public void committedDraftDoesNotAliasCallerOwnedObject()throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();FailingStore store=open(a,Records.empty());
        JSONObject draft=new JSONObject().put("content","SYNTHETIC-SAVED-DRAFT");
        Method save=MainActivity.class.getDeclaredMethod("saveDraft",String.class,JSONObject.class);save.setAccessible(true);save.invoke(a,"dream",draft);TestWork.flush(a);
        byte[] before=store.read();draft.put("content","SYNTHETIC-UNCOMMITTED-EDIT");
        assertEquals("SYNTHETIC-SAVED-DRAFT",((JSONObject)get(a,"data")).getJSONObject("drafts").getJSONObject("dream").getString("content"));assertArrayEquals(before,store.read());
        c.pause().stop().destroy();
    }
}
