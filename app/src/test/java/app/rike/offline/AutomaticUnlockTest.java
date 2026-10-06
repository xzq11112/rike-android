package app.rike.offline;

import android.os.Looper;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowToast;
import static org.junit.Assert.*;

/** Synthetic passwords only. No hardware biometric success is claimed by these tests. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28) @LooperMode(LooperMode.Mode.PAUSED)
public class AutomaticUnlockTest {
    private static void set(MainActivity a,String n,Object v)throws Exception{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);f.set(a,v);}
    private static void call(MainActivity a,String n)throws Exception{Method m=MainActivity.class.getDeclaredMethod(n);m.setAccessible(true);m.invoke(a);}
    private static EditText input(View v){if(v instanceof EditText)return (EditText)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){EditText e=input(((ViewGroup)v).getChildAt(i));if(e!=null)return e;}return null;}
    private static String text(View v){StringBuilder b=new StringBuilder();if(v instanceof TextView)b.append(((TextView)v).getText()).append('\n');if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)b.append(text(((ViewGroup)v).getChildAt(i)));return b.toString();}
    private static void time(long millis){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));}
    private static void existing(MainActivity a,String password)throws Exception{
        VaultCrypto.Created key=VaultCrypto.create(password.toCharArray());JSONObject d=Records.empty();d.getJSONArray("practiceTypes").put(new JSONObject().put("id","t").put("name","SYNTHETIC-KEPT"));
        new VaultStore(a).write(VaultCrypto.encrypt(key.session,d.toString().getBytes(StandardCharsets.UTF_8)));key.session.close();call(a,"showLocked");
    }
    @Test public void initialSetupExplainsCiphertextPasswordFingerprintAndRecovery()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();String s=text(c.get().getWindow().getDecorView());assertTrue(s.contains("请牢记主密码"));assertTrue(s.contains("备份都是密文"));assertTrue(s.contains("仅有备份文件仍无法解密"));assertTrue(s.contains("没有服务器替你找回"));assertTrue(s.contains("指纹只帮助解锁这台手机"));assertTrue(s.contains("旧备份仍需要导出时的旧密码"));assertTrue(s.contains(BuildConfig.DEBUG?"本测试版不提供恢复密钥":"提前妥善保存该密钥"));assertNotNull(TestWork.named(c.get().getWindow().getDecorView(),"使用字符键盘"));c.pause().stop().destroy();
    }
    @Test public void correctPasswordUnlocksWithoutButtonWrongPartialInputRemainsEditable()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();existing(a,"012345");byte[] before=new VaultStore(a).read();View screen=a.getWindow().getDecorView();assertNull(TestWork.named(screen,"解锁"));EditText pass=input(screen);ShadowToast.reset();
        pass.setText("012");time(550);TestWork.drain(a);assertNull(TestWork.get(a,"session"));assertTrue(pass.isEnabled());assertEquals("012",pass.getText().toString());assertEquals(0,ShadowToast.shownToastCount());assertArrayEquals(before,new VaultStore(a).read());
        pass.setText("012345");time(500);assertNull(TestWork.get(a,"session"));time(50);TestWork.drain(a);assertNotNull(TestWork.get(a,"session"));assertNotNull(TestWork.named(a.getWindow().getDecorView(),"SYNTHETIC-KEPT"));assertEquals("",pass.getText().toString());assertArrayEquals(before,new VaultStore(a).read());c.pause().stop().destroy();
    }
    private static final class BlockingStore extends VaultStore {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);int reads;
        BlockingStore(MainActivity a){super(a);}
        @Override public byte[] read()throws IOException{reads++;entered.countDown();try{if(!release.await(30,TimeUnit.SECONDS))throw new IOException();}catch(InterruptedException e){throw new IOException(e);}return super.read();}
    }
    @Test public void supersededSuccessfulProbeCannotUnlockAndDoesNotBlockInputMessages()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();existing(a,"synthetic-auto-password");BlockingStore store=new BlockingStore(a);set(a,"store",store);EditText pass=input(a.getWindow().getDecorView());pass.setText("synthetic-auto-password");time(550);assertTrue(store.entered.await(30,TimeUnit.SECONDS));
        final boolean[] message={false};new android.os.Handler(Looper.getMainLooper()).post(()->message[0]=true);Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(message[0]);assertTrue(pass.isEnabled());pass.setText("wrong-final-value");store.release.countDown();TestWork.drain(a);assertNull(TestWork.get(a,"session"));assertNull(TestWork.get(a,"data"));time(550);TestWork.drain(a);assertNull(TestWork.get(a,"session"));assertEquals(2,store.reads);
        pass.setText("synthetic-auto-password");time(550);TestWork.drain(a);assertNotNull(TestWork.get(a,"session"));assertEquals(3,store.reads);c.pause().stop().destroy();
    }
    @Test public void backgroundCancelsPendingSuccessAndWipesInput()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();existing(a,"synthetic-pause-password");BlockingStore store=new BlockingStore(a);set(a,"store",store);EditText pass=input(a.getWindow().getDecorView());pass.setText("synthetic-pause-password");time(550);assertTrue(store.entered.await(30,TimeUnit.SECONDS));c.pause();assertEquals("",pass.getText().toString());store.release.countDown();TestWork.drain(a);assertNull(TestWork.get(a,"session"));assertNull(TestWork.get(a,"data"));assertFalse((boolean)TestWork.get(a,"passwordChecking"));c.stop().destroy();
    }
    @Test public void enabledBiometricStartsAutomaticallyOnceThenAllowsPasswordFallback()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();existing(a,"synthetic-fallback-password");
        // An invalid synthetic envelope exercises automatic entry and safe fallback,
        // without pretending an emulator can supply a trusted fingerprint.
        Files.write(new File(a.getNoBackupFilesDir(),"biometric.key").toPath(),new byte[]{1});call(a,"showLocked");assertTrue((boolean)TestWork.get(a,"biometricAttempted"));assertNull(TestWork.named(a.getWindow().getDecorView(),"指纹解锁"));TestWork.drain(a);assertFalse((boolean)TestWork.get(a,"busy"));assertTrue(input(a.getWindow().getDecorView()).isEnabled());assertEquals(View.VISIBLE,TestWork.named(a.getWindow().getDecorView(),"重试指纹").getVisibility());
        call(a,"startAutomaticBiometric");assertFalse((boolean)TestWork.get(a,"busy"));input(a.getWindow().getDecorView()).setText("synthetic-fallback-password");time(550);TestWork.drain(a);assertNotNull(TestWork.get(a,"session"));c.pause();assertFalse((boolean)TestWork.get(a,"biometricAttempted"));c.resume();TestWork.drain(a);assertTrue((boolean)TestWork.get(a,"biometricAttempted"));assertNull(TestWork.get(a,"session"));c.pause().stop().destroy();
    }
}
