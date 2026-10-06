package app.rike.offline;

import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
public class OptionSaveTest {
    private static final char[] PASSWORD="012345".toCharArray();
    private static Object get(Object a,String n)throws Exception{Field f=a.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(a);}
    private static void set(Object a,String n,Object v)throws Exception{Field f=a.getClass().getDeclaredField(n);f.setAccessible(true);f.set(a,v);}
    private static void call(Object a,String n)throws Exception{Method m=a.getClass().getDeclaredMethod(n);m.setAccessible(true);m.invoke(a);}
    private static Button button(View v,String label){if(v instanceof Button&&(label.contentEquals(((Button)v).getText())||label.contentEquals(v.getContentDescription()==null?"":v.getContentDescription())))return (Button)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){Button b=button(((ViewGroup)v).getChildAt(i),label);if(b!=null)return b;}return null;}
    private static EditText input(View v){if(v instanceof EditText)return (EditText)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){EditText e=input(((ViewGroup)v).getChildAt(i));if(e!=null)return e;}return null;}
    private static class SlowStore extends VaultStore {
        volatile boolean slow,fail;
        volatile Thread writer;
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        SlowStore(MainActivity a){super(a);}
        void reset(){started=new CountDownLatch(1);release=new CountDownLatch(1);slow=true;}
        @Override public void write(byte[] bytes)throws IOException{
            if(slow){writer=Thread.currentThread();started.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IOException("synthetic timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}if(fail)throw new IOException("SYNTHETIC-PRIVATE-PATH");}
            super.write(bytes);
        }
    }
    private static SlowStore open(MainActivity a)throws Exception{
        JSONObject d=Records.empty();VaultCrypto.Created c=VaultCrypto.create(PASSWORD);SlowStore s=new SlowStore(a);
        s.write(VaultCrypto.encrypt(c.session,d.toString().getBytes(StandardCharsets.UTF_8)));
        set(a,"store",s);set(a,"session",c.session);set(a,"data",d);call(a,"showApp");s.reset();return s;
    }
    private static AlertDialog save(MainActivity a,String buttonLabel,String value){
        button(a.getWindow().getDecorView(),buttonLabel).performClick();AlertDialog d=ShadowAlertDialog.getLatestAlertDialog();
        input(d.getWindow().getDecorView()).setText(value);d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();return d;
    }
    private static void finish(MainActivity a,SlowStore s)throws Exception{
        s.release.countDown();((ExecutorService)get(a,"crypto")).submit(()->{}).get(10,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private static JSONObject disk(SlowStore s)throws Exception{
        VaultCrypto.Opened o=VaultCrypto.open(s.read(),PASSWORD);
        try{return new JSONObject(new String(o.plaintext,StandardCharsets.UTF_8));}finally{Arrays.fill(o.plaintext,(byte)0);o.session.close();}
    }
    @Test public void slowSavesKeepUiResponsiveAndPreserveTodayDraft()throws Exception{
        for(boolean type:new boolean[]{true,false}){
            ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();SlowStore s=open(a);
            View root=(View)get(a,"root");s.slow=false;EditText manual=input(root);manual.setText("17");
            TestWork.flush(a); // Persist the synthetic draft before delaying the option commit.
            s.reset();AlertDialog d=save(a,type?"添加练习类型":"添加常用练习时长",type?"SYNTHETIC-TYPE":"47");
            assertTrue(s.started.await(10,TimeUnit.SECONDS));assertNotSame(Thread.currentThread(),s.writer);
            assertFalse(d.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());assertEquals("保存中…",d.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            AtomicBoolean frame=new AtomicBoolean();new Handler(Looper.getMainLooper()).post(()->frame.set(true));Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(frame.get());
            assertEquals(0,((JSONObject)get(a,"data")).getJSONArray(type?"practiceTypes":"durationPresets").length());
            finish(a,s);assertFalse(d.isShowing());assertEquals(type?"17":"",TestWork.draft(a,"checkIn").optString("manual"));if(!type)assertEquals(47,TestWork.draft(a,"checkIn").getInt("preset"));
            assertSame(root,get(a,"root"));assertEquals(type?"17":"",manual.getText().toString());assertNull(get(a,"managedEntity"));
            assertNotNull(button(a.getWindow().getDecorView(),type?"SYNTHETIC-TYPE":"47 分钟"));assertEquals(1,disk(s).getJSONArray(type?"practiceTypes":"durationPresets").length());c.pause().stop().destroy();
        }
    }
    @Test public void failedSavePreservesDiskAndMemoryAllowsRetryAndRedactsDiagnostics()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();SlowStore s=open(a);
        String before=((JSONObject)get(a,"data")).toString();byte[] ciphertext=s.read();s.fail=true;
        AlertDialog d=save(a,"添加练习类型","SYNTHETIC-TYPE");assertTrue(s.started.await(10,TimeUnit.SECONDS));finish(a,s);
        assertTrue(d.isShowing());assertTrue(d.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());assertEquals(before,((JSONObject)get(a,"data")).toString());assertArrayEquals(ciphertext,s.read());
        String report=((Diagnostics)get(a,"diagnostics")).report();assertTrue(report.contains("VAULT_WRITE_FAILED"));assertFalse(report.contains("SYNTHETIC"));
        s.reset();s.fail=false;d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(s.started.await(10,TimeUnit.SECONDS));finish(a,s);
        assertEquals(1,disk(s).getJSONArray("practiceTypes").length());c.pause().stop().destroy();
    }
    @Test public void backgroundDuringSaveDoesNotRestoreUnlockedScreen()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();SlowStore s=open(a);
        AlertDialog d=save(a,"添加常用练习时长","47");assertTrue(s.started.await(10,TimeUnit.SECONDS));c.pause();
        assertFalse(d.isShowing());assertNull(get(a,"data"));assertNull(get(a,"session"));finish(a,s);
        assertNull(get(a,"data"));assertNull(get(a,"session"));assertNotNull(button(a.getWindow().getDecorView(),"使用字符键盘"));
        assertEquals(1,disk(s).getJSONArray("durationPresets").length());c.stop().destroy();
    }
}
