package app.rike.offline;

import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
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
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28) @LooperMode(LooperMode.Mode.PAUSED)
public class UpdateFlowTest {
    private static void set(MainActivity a,String name,Object value)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(a,value);}
    private static void open(MainActivity a)throws Exception {
        JSONObject data=Records.empty();VaultCrypto.Created key=VaultCrypto.create("synthetic-update-password".toCharArray());
        new VaultStore(a).write(VaultCrypto.encrypt(key.session,data.toString().getBytes(StandardCharsets.UTF_8)));
        set(a,"session",key.session);set(a,"data",data);Method show=MainActivity.class.getDeclaredMethod("showApp");show.setAccessible(true);show.invoke(a);
        TestWork.navigate(a,5);click(a,"维护");click(a,"版本与更新");
    }
    private static void click(MainActivity a,String label){View v=TestWork.named(a.getWindow().getDecorView(),label);assertNotNull(label,v);v.performClick();}
    private static void settle(MainActivity a)throws Exception {
        ((ExecutorService)TestWork.get(a,"updateWorker")).submit(()->{}).get(5,TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    @Test public void onlyExplicitCheckFetchesAndDownloadIntentContainsOnlyPublicUrl()throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();
        try {
            AtomicInteger calls=new AtomicInteger();set(a,"updateRequestFactory",(Supplier<UpdateChecker.Request>)()->new UpdateChecker.Request(){
                @Override UpdateChecker.Release load(){calls.incrementAndGet();return new UpdateChecker.Release("99.0.0","https://github.com/xzq11112/rike-android/releases/download/v99.0.0/rike-99.0.0-release.apk");}
            });
            open(a);byte[] before=new VaultStore(a).read();assertEquals(0,calls.get());assertNull(TestWork.get(a,"updateWorker"));
            click(a,"前往 GitHub 下载最新版");Intent fallback=Shadows.shadowOf(a).getNextStartedActivity();assertEquals(UpdateChecker.LATEST_PAGE,fallback.getDataString());assertEquals(0,calls.get());
            click(a,"检查更新");settle(a);assertEquals(1,calls.get());assertNotNull(TestWork.named(a.getWindow().getDecorView(),"发现新版本 v99.0.0"));
            click(a,"下载 v99.0.0 APK");Intent download=Shadows.shadowOf(a).getNextStartedActivity();
            assertEquals(Intent.ACTION_VIEW,download.getAction());assertTrue(download.hasCategory(Intent.CATEGORY_BROWSABLE));
            assertTrue(download.getDataString().endsWith("rike-99.0.0-release.apk"));assertNull(download.getExtras());assertNull(download.getClipData());
            assertArrayEquals(before,new VaultStore(a).read());
            c.pause();assertNull(TestWork.get(a,"session"));assertNull(TestWork.get(a,"data"));
        } finally {c.stop().destroy();}
    }
    @Test public void failureAllowsRetryAndDoesNotExposeExceptionText()throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();
        try {
            AtomicInteger calls=new AtomicInteger();set(a,"updateRequestFactory",(Supplier<UpdateChecker.Request>)()->new UpdateChecker.Request(){
                @Override UpdateChecker.Release load()throws IOException{
                    if(calls.incrementAndGet()==1)throw new IOException("SYNTHETIC-EXCEPTION-PRIVATE");
                    return new UpdateChecker.Release(BuildConfig.VERSION_NAME.replace("-test",""),null);
                }
            });
            open(a);click(a,"检查更新");settle(a);assertEquals("检查失败，请检查网络后重试，或前往 GitHub 查看。",TestWork.get(a,"updateMessage"));
            assertTrue(TestWork.named(a.getWindow().getDecorView(),"检查更新").isEnabled());
            click(a,"检查更新");settle(a);assertEquals(2,calls.get());assertTrue(((String)TestWork.get(a,"updateMessage")).contains("暂未提供正式 APK"));
            assertNotNull(TestWork.named(a.getWindow().getDecorView(),"前往 GitHub 下载最新版"));
        } finally {c.pause().stop().destroy();}
    }
    @Test public void duplicateCheckIsIgnoredAndLateResultCannotReopenLockedScreen()throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();
        CountDownLatch started=new CountDownLatch(1),finish=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger(),cancelled=new AtomicInteger();
        try {
            set(a,"updateRequestFactory",(Supplier<UpdateChecker.Request>)()->new UpdateChecker.Request(){
                @Override UpdateChecker.Release load(){calls.incrementAndGet();started.countDown();boolean done=false;
                    while(!done){try{done=finish.await(5,TimeUnit.SECONDS);}catch(InterruptedException ignored){}}
                    return new UpdateChecker.Release("99.0.0",null);
                }
                @Override void cancel(){cancelled.incrementAndGet();}
            });
            open(a);click(a,"检查更新");assertTrue(started.await(5,TimeUnit.SECONDS));
            assertFalse(((Button)TestWork.named(a.getWindow().getDecorView(),"正在检查…")).isEnabled());
            Method check=MainActivity.class.getDeclaredMethod("checkForUpdates");check.setAccessible(true);check.invoke(a);assertEquals(1,calls.get());
            c.pause();finish.countDown();settle(a);assertEquals(1,cancelled.get());
            assertNull(TestWork.get(a,"data"));assertNull(TestWork.get(a,"latestRelease"));assertEquals("尚未检查更新",TestWork.get(a,"updateMessage"));
            assertNull(TestWork.named(a.getWindow().getDecorView(),"发现新版本 v99.0.0"));
        } finally {finish.countDown();c.stop().destroy();}
    }
}
