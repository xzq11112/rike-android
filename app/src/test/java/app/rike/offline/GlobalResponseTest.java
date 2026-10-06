package app.rike.offline;

import android.os.*;
import android.view.*;
import android.widget.*;
import android.app.AlertDialog;
import java.io.IOException;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28) @LooperMode(LooperMode.Mode.PAUSED)
public class GlobalResponseTest {
    private static void set(MainActivity a,String name,Object value)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(a,value);}
    private static void call(MainActivity a,String name)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(a);}
    private static Button button(View v,String name){if(v instanceof Button&&(name.contentEquals(((Button)v).getText())||name.contentEquals(v.getContentDescription()==null?"":v.getContentDescription())))return (Button)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){Button b=button(((ViewGroup)v).getChildAt(i),name);if(b!=null)return b;}return null;}
    private static EditText input(View v){if(v instanceof EditText)return (EditText)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){EditText e=input(((ViewGroup)v).getChildAt(i));if(e!=null)return e;}return null;}
    private static int count(View v){int n=1;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)n+=count(((ViewGroup)v).getChildAt(i));return n;}
    private static class TrackingStore extends VaultStore {
        volatile int writes;volatile boolean mainWrite;
        TrackingStore(MainActivity a){super(a);}
        @Override public void write(byte[] bytes)throws IOException{writes++;if(Looper.myLooper()==Looper.getMainLooper())mainWrite=true;super.write(bytes);}
    }
    private static TrackingStore open(MainActivity a,JSONObject d)throws Exception{
        VaultCrypto.Created key=VaultCrypto.create("012345".toCharArray());TrackingStore store=new TrackingStore(a);
        store.write(VaultCrypto.encrypt(key.session,d.toString().getBytes(StandardCharsets.UTF_8)));store.writes=0;store.mainWrite=false;
        set(a,"store",store);set(a,"session",key.session);set(a,"data",d);call(a,"showApp");return store;
    }
    @Test public void typingCoalescesAndLockFlushesWithoutBlockingOrResurrectingUi()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();TrackingStore store=open(a,Records.empty());
        ExecutorService worker=(ExecutorService)TestWork.get(a,"crypto");CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        worker.execute(()->{started.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});assertTrue(started.await(10,TimeUnit.SECONDS));
        EditText manual=input(a.getWindow().getDecorView());for(int i=1;i<=42;i++)manual.setText(String.valueOf(i));
        assertEquals("42",TestWork.draft(a,"checkIn").getString("manual"));assertEquals(0,store.writes);
        AtomicBoolean responsive=new AtomicBoolean();new Handler(Looper.getMainLooper()).post(()->responsive.set(true));Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(responsive.get());
        VaultCrypto.Session old=(VaultCrypto.Session)TestWork.get(a,"session");c.pause();assertNull(TestWork.get(a,"session"));assertTrue(old.isClosed());assertNull(TestWork.get(a,"data"));assertNull(TestWork.get(a,"recordIndex"));
        release.countDown();TestWork.drain(a);assertEquals(1,store.writes);assertFalse(store.mainWrite);assertNull(TestWork.get(a,"data"));assertNull(TestWork.get(a,"recordIndex"));
        VaultCrypto.Opened restored=VaultCrypto.open(store.read(),"012345".toCharArray());assertEquals("42",new JSONObject(new String(restored.plaintext,StandardCharsets.UTF_8)).getJSONObject("drafts").getJSONObject("checkIn").getString("manual"));restored.session.close();c.stop().destroy();
    }
    @Test public void thousandsOfRecordsAndExpandedHeatmapsKeepViewCountBounded()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();JSONObject d=Records.empty();
        for(int i=0;i<3000;i++)d.getJSONArray("checkIns").put(new JSONObject().put("id","synthetic-"+i).put("practiceDate",LocalDate.now().minusDays(i%600).toString()).put("practiceTypeName","SYNTHETIC").put("durationMinutes",10).put("note","synthetic notes"));
        open(a,d);View root=a.getWindow().getDecorView();assertTrue(count(root)<200);TestWork.navigate(a,3);assertTrue(count(a.getWindow().getDecorView())<400);
        button(a.getWindow().getDecorView(),"记录").performClick();assertTrue(count(a.getWindow().getDecorView())<360);assertNull(TestWork.named(a.getWindow().getDecorView(),"下一页"));
        assertEquals(3000,((JSONObject)TestWork.get(a,"data")).getJSONArray("checkIns").length());c.pause().stop().destroy();
    }
    @Test public void directUiVaultWriteIsRejected()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();TrackingStore store=open(a,Records.empty());
        Method m=MainActivity.class.getDeclaredMethod("persist",VaultCrypto.Session.class,JSONObject.class);m.setAccessible(true);
        try{m.invoke(a,TestWork.get(a,"session"),TestWork.get(a,"data"));fail("UI write must be rejected");}catch(InvocationTargetException e){assertTrue(e.getCause() instanceof IllegalStateException);}assertEquals(0,store.writes);c.pause().stop().destroy();
    }
    @Test public void virtualHeatmapDaysRemainClickableAndAccessible()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();AtomicBoolean clicked=new AtomicBoolean();
        HeatmapView grid=new HeatmapView(a,PracticeHeatmap.year(2024,java.util.Collections.emptyMap()),19,false,false,d->clicked.set(d.date.equals(LocalDate.of(2024,2,29))));
        grid.measure(View.MeasureSpec.makeMeasureSpec(380,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));grid.layout(0,0,380,grid.getMeasuredHeight());
        android.view.accessibility.AccessibilityNodeProvider p=grid.getAccessibilityNodeProvider();assertEquals(366,p.createAccessibilityNodeInfo(-1).getChildCount());assertTrue(p.createAccessibilityNodeInfo(59).getContentDescription().toString().contains("2024-02-29"));assertTrue(p.performAction(59,android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,null));assertTrue(clicked.get());c.pause().stop().destroy();
    }
}
