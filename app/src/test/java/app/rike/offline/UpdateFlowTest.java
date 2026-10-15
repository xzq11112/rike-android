package app.rike.offline;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.ScrollView;
import java.io.IOException;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28) @LooperMode(LooperMode.Mode.PAUSED)
public class UpdateFlowTest {
    private static void set(MainActivity a,String name,Object value)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(a,value);}
    private static void open(MainActivity a)throws Exception {
        JSONObject data=Records.empty();VaultCrypto.Created key=VaultCrypto.create("synthetic-update-password".toCharArray());
        new VaultStore(a).write(VaultCrypto.encrypt(key.session,data.toString().getBytes(StandardCharsets.UTF_8)));
        set(a,"session",key.session);set(a,"data",data);call(a,"showApp");
    }
    private static void call(MainActivity a,String name)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(a);}
    private static View named(View root,String label){View v=TestWork.named(root,label);assertNotNull(label,v);return v;}
    private static void click(MainActivity a,String label){named(a.getWindow().getDecorView(),label).performClick();}
    private static AlertDialog prompt(){AlertDialog d=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(d);assertTrue(d.isShowing());return d;}
    private static void action(AlertDialog d,String label){named(d.getWindow().getDecorView(),label).performClick();}
    private static void version(MainActivity a){TestWork.navigate(a,5);click(a,"维护");click(a,"版本与更新");}
    private static void settle(MainActivity a)throws Exception {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        ExecutorService worker=(ExecutorService)TestWork.get(a,"updateWorker");
        if(worker!=null)worker.submit(()->{}).get(5,TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private static void synthetic(MainActivity a,AtomicInteger calls)throws Exception {
        set(a,"updateRequestFactory",(Supplier<UpdateChecker.Request>)()->new UpdateChecker.Request(){
            @Override UpdateChecker.Release load(){calls.incrementAndGet();return new UpdateChecker.Release("99.0.0","synthetic-android-asset");}
        });
    }
    @Test public void startupFetchesOnceAfterUnlockAndOpensOnlyExactPublicReleasePage()throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=TestWork.offline(c.get());
        try {
            AtomicInteger calls=new AtomicInteger();synthetic(a,calls);settle(a);assertEquals(0,calls.get());
            open(a);byte[] before=new VaultStore(a).read();settle(a);assertEquals(1,calls.get());
            AlertDialog d=prompt();named(d.getWindow().getDecorView(),"发现新版本 v99.0.0");
            action(d,"查看更新");Intent page=Shadows.shadowOf(a).getNextStartedActivity();
            assertEquals(Intent.ACTION_VIEW,page.getAction());assertTrue(page.hasCategory(Intent.CATEGORY_BROWSABLE));
            assertEquals(UpdateChecker.RELEASE_PREFIX+"v99.0.0",page.getDataString());assertNull(page.getExtras());assertNull(page.getClipData());assertFalse(d.isShowing());
            assertArrayEquals(before,new VaultStore(a).read());
            long opening=((StartupUpdateFlow)TestWork.get(a,"startupUpdates")).session();
            c.pause().stop().start().resume();open(a);settle(a);
            assertEquals(opening,((StartupUpdateFlow)TestWork.get(a,"startupUpdates")).session());assertEquals(1,calls.get());assertFalse(d.isShowing());
        } finally {c.pause().stop().destroy();}
    }
    @Test public void laterDefersUntilNextOpeningAndSkippedVersionStillSupportsManualCheck()throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=TestWork.offline(c.get());
        try {
            a.getPreferences(0).edit().clear().commit();AtomicInteger calls=new AtomicInteger();synthetic(a,calls);open(a);settle(a);
            AlertDialog first=prompt();action(first,"稍后");call(a,"scheduleStartupUpdate");settle(a);assertEquals(1,calls.get());assertFalse(first.isShowing());
            c.pause().stop().start().resume();open(a);settle(a);assertEquals(2,calls.get());
            action(prompt(),"跳过此版本");assertEquals("v99.0.0",a.getPreferences(0).getString("skippedUpdateTag",""));
            c.pause().stop().start().resume();open(a);settle(a);assertEquals(3,calls.get());assertFalse(ShadowAlertDialog.getLatestAlertDialog().isShowing());
            version(a);click(a,"检查更新");settle(a);assertEquals(4,calls.get());action(prompt(),"稍后");
            click(a,"查看 v99.0.0 更新");assertEquals(UpdateChecker.RELEASE_PREFIX+"v99.0.0",Shadows.shadowOf(a).getNextStartedActivity().getDataString());
        } finally {a.getPreferences(0).edit().clear().commit();c.pause().stop().destroy();}
    }
    @Test public void automaticFailureIsSilentAndManualFailureAllowsRetryWithoutExceptionText()throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=TestWork.offline(c.get());
        try {
            AtomicInteger calls=new AtomicInteger();set(a,"updateRequestFactory",(Supplier<UpdateChecker.Request>)()->new UpdateChecker.Request(){
                @Override UpdateChecker.Release load()throws IOException{calls.incrementAndGet();throw new IOException("SYNTHETIC-EXCEPTION-PRIVATE");}
            });
            open(a);settle(a);assertEquals(1,calls.get());assertTrue(((java.util.List<?>)TestWork.get(a,"dialogs")).isEmpty());
            call(a,"showApp");settle(a);assertEquals(1,calls.get());
            version(a);click(a,"检查更新");settle(a);assertEquals(2,calls.get());
            assertEquals("检查失败，请检查网络后重试，或前往 GitHub 查看。",TestWork.get(a,"updateMessage"));assertTrue(named(a.getWindow().getDecorView(),"检查更新").isEnabled());
            click(a,"检查更新");settle(a);assertEquals(3,calls.get());click(a,"前往 GitHub 发布页");assertEquals(UpdateChecker.LATEST_PAGE,Shadows.shadowOf(a).getNextStartedActivity().getDataString());
        } finally {c.pause().stop().destroy();}
    }
    @Test public void duplicateCheckIsIgnoredAndLateResultCannotReopenLockedScreen()throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=TestWork.offline(c.get());
        CountDownLatch started=new CountDownLatch(1),finish=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger(),cancelled=new AtomicInteger();
        try {
            set(a,"updateRequestFactory",(Supplier<UpdateChecker.Request>)()->new UpdateChecker.Request(){
                @Override UpdateChecker.Release load(){calls.incrementAndGet();started.countDown();boolean done=false;while(!done){try{done=finish.await(5,TimeUnit.SECONDS);}catch(InterruptedException ignored){}}return new UpdateChecker.Release("99.0.0","synthetic");}
                @Override void cancel(){cancelled.incrementAndGet();}
            });
            open(a);Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(started.await(5,TimeUnit.SECONDS));
            call(a,"checkForUpdates");assertEquals(1,calls.get());c.pause();finish.countDown();settle(a);assertEquals(1,cancelled.get());
            assertNull(TestWork.get(a,"data"));assertNull(TestWork.get(a,"latestRelease"));assertEquals("尚未检查更新",TestWork.get(a,"updateMessage"));assertTrue(((java.util.List<?>)TestWork.get(a,"dialogs")).isEmpty());
        } finally {finish.countDown();c.stop().destroy();}
    }
    @Test public void editingDefersPromptUntilReadyAndDialogKeepsActionsOutsideScrollableCopy()throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=TestWork.offline(c.get());
        try {
            AtomicInteger calls=new AtomicInteger();synthetic(a,calls);open(a);set(a,"journalEditing",true);settle(a);
            assertTrue(((java.util.List<?>)TestWork.get(a,"dialogs")).isEmpty());set(a,"journalEditing",false);call(a,"scheduleStartupUpdate");settle(a);
            AlertDialog d=prompt();View root=d.getWindow().getDecorView();
            ScrollView copy=(ScrollView)named(root,"本次更新说明");named(copy,"发现新版本 v99.0.0");named(copy,UpdateChecker.FALLBACK_HIGHLIGHTS);
            for(String label:new String[]{"查看更新","稍后","跳过此版本"}){View button=named(root,label);assertNull(TestWork.named(copy,label));assertTrue(((android.widget.TextView)button).getMinHeight()>=48*a.getResources().getDisplayMetrics().density);}
            d.cancel();settle(a);assertFalse(d.isShowing());assertEquals(1,calls.get());
        } finally {c.pause().stop().destroy();}
    }
    @Test @Config(qualifiers="w568dp-h320dp-land") public void smallLandscapeLargeTextKeepsAllActionsVisibleInBothThemes()throws Exception {
        for(boolean dark:new boolean[]{false,true}){
            ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=TestWork.offline(c.get());
            try {
                android.content.res.Configuration config=new android.content.res.Configuration(a.getResources().getConfiguration());config.fontScale=2f;
                a.getResources().updateConfiguration(config,a.getResources().getDisplayMetrics());set(a,"dark",dark);
                AtomicInteger calls=new AtomicInteger();synthetic(a,calls);open(a);settle(a);AlertDialog d=prompt();
                ScrollView copy=(ScrollView)named(d.getWindow().getDecorView(),"本次更新说明");View layout=(View)copy.getParent();
                int width=d.getWindow().getAttributes().width,height=d.getWindow().getAttributes().height;
                layout.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));layout.layout(0,0,width,height);
                assertTrue("copy has scroll space",copy.getHeight()>0);
                for(String label:new String[]{"查看更新","稍后","跳过此版本"}){
                    View button=named(layout,label);assertTrue(label,button.getHeight()>=48*a.getResources().getDisplayMetrics().density);
                    int bottom=button.getBottom();View parent=(View)button.getParent();while(parent!=layout){bottom+=parent.getTop();parent=(View)parent.getParent();}
                    assertTrue(label+" within dialog",bottom<=height-layout.getPaddingBottom());
                }
            } finally {c.pause().stop().destroy();}
        }
    }
}
