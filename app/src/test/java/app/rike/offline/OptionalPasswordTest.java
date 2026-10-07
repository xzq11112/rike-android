package app.rike.offline;

import android.app.AlertDialog;
import android.net.Uri;
import android.os.Looper;
import android.util.AtomicFile;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
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

/** Real UI and encrypted files with synthetic device wrapping keys only.
 * This verifies automatic local access, not hardware Keystore or fingerprint trust. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28) @LooperMode(LooperMode.Mode.PAUSED)
public class OptionalPasswordTest {
    private static final String PASSWORD="shanhai-yueliang-senlin-huoche-synthetic";
    private static final String TYPE="SYNTHETIC-OPTIONAL-TYPE";
    private static final String NOTE="SYNTHETIC-PRIVATE-RECORD";

    private static final class SyntheticKeys implements DeviceVault.Keys {
        private final Map<String,SecretKey> keys=new HashMap<>();
        public SecretKey create(String alias)throws Exception{
            KeyGenerator generator=KeyGenerator.getInstance("AES");generator.init(256);
            SecretKey key=generator.generateKey();keys.put(alias,key);return key;
        }
        public SecretKey get(String alias){return keys.get(alias);}
        public void remove(String alias){keys.remove(alias);}
    }
    private static final class FailingStore extends VaultStore {
        boolean fail=true;
        FailingStore(MainActivity a){super(a);}
        @Override public void write(byte[] ciphertext)throws IOException{
            if(fail)throw new IOException("SYNTHETIC-WRITE-FAILURE");super.write(ciphertext);
        }
    }
    private static void set(MainActivity a,String name,Object value)throws Exception{
        Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(a,value);
    }
    private static void call(MainActivity a,String name)throws Exception{
        Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(a);
    }
    private static DeviceVault injectDevice(MainActivity a,SyntheticKeys keys)throws Exception{
        DeviceVault device=new DeviceVault(new AtomicFile(new File(a.getNoBackupFilesDir(),"device.key")),keys);
        set(a,"device",device);return device;
    }
    private static ActivityController<MainActivity> start(SyntheticKeys keys)throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).create();
        injectDevice(c.get(),keys);c.start().resume().visible();TestWork.drain(c.get());return c;
    }
    private static void close(ActivityController<MainActivity> c)throws Exception{
        c.pause().stop().destroy();TestWork.drain(c.get());
    }
    private static View screen(MainActivity a){return a.getWindow().getDecorView();}
    private static void click(View root,String label){
        View v=TestWork.named(root,label);assertNotNull(label,v);assertTrue(label,v.isEnabled());v.performClick();
    }
    private static void click(MainActivity a,String label){click(screen(a),label);}
    private static AlertDialog dialog(){AlertDialog d=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(d);assertTrue(d.isShowing());return d;}
    private static void positive(MainActivity a,AlertDialog d)throws Exception{
        assertTrue(d.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();TestWork.drain(a);
    }
    private static void skip(MainActivity a)throws Exception{
        click(a,"暂不设置密码，直接使用");TestWork.drain(a);
        assertTrue(new VaultStore(a).exists());assertNotNull(TestWork.get(a,"session"));assertTrue((boolean)TestWork.get(a,"deviceMode"));
    }
    private static void saveRecord(MainActivity a)throws Exception{
        click(a,"添加练习类型");AlertDialog add=dialog();TestWork.views(add.getWindow().getDecorView(),EditText.class).get(0).setText(TYPE);positive(a,add);
        click(a,"补记日期或添加备注");List<EditText> inputs=TestWork.views(screen(a),EditText.class);inputs.get(0).setText("17");inputs.get(1).setText(NOTE);
        click(a,"保存练习");TestWork.drain(a);assertEquals(1,((JSONObject)TestWork.get(a,"data")).getJSONArray("checkIns").length());
    }
    private static JSONObject decoded(VaultCrypto.Opened opened)throws Exception{
        try{return Records.validate(new JSONObject(new String(opened.plaintext,StandardCharsets.UTF_8)));}
        finally{Arrays.fill(opened.plaintext,(byte)0);opened.session.close();}
    }
    private static void assertRecord(JSONObject data)throws Exception{
        assertEquals(1,data.getJSONArray("checkIns").length());JSONObject row=data.getJSONArray("checkIns").getJSONObject(0);
        assertEquals(TYPE,row.getString("practiceTypeName"));assertEquals(17,row.getInt("durationMinutes"));assertEquals(NOTE,row.getString("note"));
    }
    private static void enterNewPassword(AlertDialog d){
        List<EditText> inputs=TestWork.views(d.getWindow().getDecorView(),EditText.class);assertEquals(2,inputs.size());inputs.get(0).setText(PASSWORD);inputs.get(1).setText(PASSWORD);
    }
    private static String visibleRecovery(AlertDialog d){
        for(TextView text:TestWork.views(d.getWindow().getDecorView(),TextView.class)){
            String value=text.getText().toString().replaceAll("\\s","");if(value.matches("[0-9a-fA-F]{64}"))return value;
        }
        throw new AssertionError("The release UI did not display the recovery key");
    }

    @Test public void skipPasswordSavesEncryptedRecordsAndReopensOnNewActivity()throws Exception{
        SyntheticKeys keys=new SyntheticKeys();ActivityController<MainActivity> first=start(keys),second=null;
        try{
            MainActivity a=first.get();skip(a);saveRecord(a);byte[] file=new VaultStore(a).read();
            assertFalse(new String(file,StandardCharsets.ISO_8859_1).contains(NOTE));
            assertRecord(decoded(((DeviceVault)TestWork.get(a,"device")).open(file)));
            close(first);first=null;second=start(keys);MainActivity reopened=second.get();
            assertNotNull(TestWork.get(reopened,"session"));assertTrue((boolean)TestWork.get(reopened,"deviceMode"));assertRecord((JSONObject)TestWork.get(reopened,"data"));
            assertArrayEquals(file,new VaultStore(reopened).read());TestWork.navigate(reopened,1);assertNotNull(TestWork.named(screen(reopened),NOTE));
        }finally{if(second!=null)close(second);if(first!=null)close(first);}
    }

    @Test public void oldPasswordVaultDoesNotOpenThroughAnUnrelatedDeviceWrapper()throws Exception{
        SyntheticKeys keys=new SyntheticKeys();ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).create();MainActivity a=c.get();
        try{
            DeviceVault device=injectDevice(a,keys);VaultCrypto.Created unrelated=VaultCrypto.create("synthetic-unrelated-device".toCharArray());
            try{device.enroll(unrelated.session);}finally{unrelated.session.close();}
            VaultCrypto.Created password=VaultCrypto.create(PASSWORD.toCharArray());JSONObject data=Records.empty();data.getJSONArray("practiceTypes").put(new JSONObject().put("id","t").put("name",TYPE));
            byte[] file;try{file=VaultCrypto.encrypt(password.session,data.toString().getBytes(StandardCharsets.UTF_8));new VaultStore(a).write(file);}finally{password.session.close();}
            c.start().resume().visible();TestWork.drain(a);
            assertNull(TestWork.get(a,"session"));assertNull(TestWork.get(a,"data"));assertFalse((boolean)TestWork.get(a,"deviceMode"));assertFalse((boolean)TestWork.get(a,"busy"));assertArrayEquals(file,new VaultStore(a).read());
            assertNull(TestWork.named(screen(a),"暂不设置密码，直接使用"));
            TestWork.views(screen(a),EditText.class).get(0).setText(PASSWORD);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(550));TestWork.drain(a);
            assertNotNull(TestWork.get(a,"session"));assertFalse((boolean)TestWork.get(a,"deviceMode"));assertNotNull(TestWork.named(screen(a),TYPE));
        }finally{close(c);}
    }

    @Test public void passwordlessModeCannotEnableFingerprintOrExportAnUnrecoverableBackup()throws Exception{
        ActivityController<MainActivity> c=start(new SyntheticKeys());MainActivity a=c.get();
        try{
            skip(a);byte[] before=new VaultStore(a).read();TestWork.navigate(a,5);
            assertNotNull(TestWork.named(screen(a),"设置密码与恢复密钥"));View fingerprint=TestWork.named(screen(a),"启用指纹解锁");assertNotNull(fingerprint);assertFalse(fingerprint.isEnabled());
            Method enroll=MainActivity.class.getDeclaredMethod("authenticateBiometric",boolean.class);enroll.setAccessible(true);enroll.invoke(a,true);TestWork.drain(a);
            assertFalse(((BiometricVault)TestWork.get(a,"biometric")).enabled());assertFalse((boolean)TestWork.get(a,"busy"));assertNull(TestWork.get(a,"biometricCancel"));
            TestWork.navigate(a,4);click(a,"导出加密备份");assertNull(Shadows.shadowOf(a).getNextStartedActivity());
            AlertDialog setup=dialog();assertNotNull(TestWork.named(setup.getWindow().getDecorView(),"设置主密码"));setup.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();
            // A stale file-picker result must also be rejected by the write path.
            File output=new File(a.getCacheDir(),"synthetic-passwordless-export.rike");set(a,"pendingAction",103);set(a,"pendingUri",Uri.fromFile(output));call(a,"consumePending");TestWork.drain(a);
            assertFalse(output.exists());assertArrayEquals(before,new VaultStore(a).read());assertNull(TestWork.get(a,"lastExportUri"));assertTrue((boolean)TestWork.get(a,"deviceMode"));
        }finally{close(c);}
    }

    @Test public void laterPasswordSetupRekeysRecordsAndRequiresReleaseRecoveryConfirmation()throws Exception{
        ActivityController<MainActivity> c=start(new SyntheticKeys());MainActivity a=c.get();
        try{
            skip(a);saveRecord(a);DeviceVault device=(DeviceVault)TestWork.get(a,"device");byte[] before=new VaultStore(a).read();
            VaultCrypto.Session old=(VaultCrypto.Session)TestWork.get(a,"session");String rows=((JSONObject)TestWork.get(a,"data")).getJSONArray("checkIns").toString();
            TestWork.navigate(a,5);click(a,"设置密码与恢复密钥");AlertDialog setup=dialog();enterNewPassword(setup);positive(a,setup);String recovery=null;
            if(!BuildConfig.DEBUG){
                AlertDialog save=dialog();recovery=visibleRecovery(save);assertArrayEquals(before,new VaultStore(a).read());assertTrue((boolean)TestWork.get(a,"deviceMode"));
                positive(a,save);AlertDialog verify=dialog();EditText value=TestWork.views(verify.getWindow().getDecorView(),EditText.class).get(0);
                value.setText("0".repeat(64).equals(recovery)?"1".repeat(64):"0".repeat(64));positive(a,verify);assertTrue(verify.isShowing());assertNotNull(value.getError());assertArrayEquals(before,new VaultStore(a).read());
                value.setText(recovery);positive(a,verify);
            }
            assertFalse((boolean)TestWork.get(a,"deviceMode"));assertFalse((boolean)TestWork.get(a,"busy"));assertTrue(old.isClosed());assertFalse(device.enabled());assertNull(TestWork.get(a,"pendingProtectionSession"));
            assertEquals(rows,((JSONObject)TestWork.get(a,"data")).getJSONArray("checkIns").toString());byte[] protectedFile=new VaultStore(a).read();assertFalse(Arrays.equals(before,protectedFile));
            assertRecord(decoded(VaultCrypto.open(protectedFile,PASSWORD.toCharArray())));if(recovery!=null)assertRecord(decoded(VaultCrypto.recover(protectedFile,recovery)));
            assertTrue(TestWork.named(screen(a),"启用指纹解锁").isEnabled());c.pause().resume();TestWork.drain(a);assertNull(TestWork.get(a,"session"));
            TestWork.views(screen(a),EditText.class).get(0).setText(PASSWORD);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(550));TestWork.drain(a);assertRecord((JSONObject)TestWork.get(a,"data"));
        }finally{close(c);}
    }

    @Test public void cancellingPasswordSetupWhileWorkerIsQueuedDoesNotLeaveTheAppBusy()throws Exception{
        ActivityController<MainActivity> c=start(new SyntheticKeys());MainActivity a=c.get();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try{
            skip(a);byte[] before=new VaultStore(a).read();TestWork.navigate(a,5);click(a,"设置密码与恢复密钥");AlertDialog setup=dialog();enterNewPassword(setup);
            ((ExecutorService)TestWork.get(a,"crypto")).execute(()->{entered.countDown();try{if(!release.await(30,TimeUnit.SECONDS))throw new AssertionError("Synthetic worker gate timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}});
            assertTrue(entered.await(30,TimeUnit.SECONDS));setup.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue((boolean)TestWork.get(a,"busy"));
            setup.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(setup.isShowing());
            release.countDown();TestWork.drain(a);assertFalse((boolean)TestWork.get(a,"busy"));assertTrue((boolean)TestWork.get(a,"deviceMode"));assertNull(TestWork.get(a,"pendingProtectionSession"));assertArrayEquals(before,new VaultStore(a).read());
            click(a,"设置密码与恢复密钥");assertTrue(dialog().isShowing());
        }finally{release.countDown();TestWork.drain(a);close(c);}
    }

    @Test public void firstCiphertextWriteFailureAllowsCreatingAgainWithTheDeviceWrapper()throws Exception{
        ActivityController<MainActivity> c=start(new SyntheticKeys());MainActivity a=c.get();
        try{
            FailingStore store=new FailingStore(a);set(a,"store",store);click(a,"暂不设置密码，直接使用");TestWork.drain(a);
            assertFalse(store.exists());assertNull(TestWork.get(a,"session"));assertFalse((boolean)TestWork.get(a,"busy"));
            DeviceVault device=(DeviceVault)TestWork.get(a,"device");assertTrue(device.enabled());assertTrue(TestWork.named(screen(a),"暂不设置密码，直接使用").isEnabled());
            store.fail=false;skip(a);byte[] file=store.read();assertTrue(device.matches(file));assertTrue(Records.isEmpty(decoded(device.open(file))));
        }finally{close(c);}
    }

    @Test public void failedPasswordRekeyPreservesLocalAccessAndCanBeRetried()throws Exception{
        ActivityController<MainActivity> c=start(new SyntheticKeys());MainActivity a=c.get();
        try{
            skip(a);saveRecord(a);TestWork.navigate(a,2);String draftDay=(String)TestWork.get(a,"journalDate");
            TestWork.views(screen(a),EditText.class).get(0).setText("SYNTHETIC-PRIVATE-DRAFT");TestWork.flush(a);
            FailingStore store=new FailingStore(a);set(a,"store",store);byte[] before=store.read();
            DeviceVault device=(DeviceVault)TestWork.get(a,"device");VaultCrypto.Session original=(VaultCrypto.Session)TestWork.get(a,"session");
            TestWork.navigate(a,5);click(a,"设置密码与恢复密钥");AlertDialog setup=dialog();enterNewPassword(setup);positive(a,setup);String recovery=null;
            if(!BuildConfig.DEBUG){
                AlertDialog save=dialog();recovery=visibleRecovery(save);positive(a,save);AlertDialog verify=dialog();
                TestWork.views(verify.getWindow().getDecorView(),EditText.class).get(0).setText(recovery);positive(a,verify);
            }
            assertArrayEquals(before,store.read());assertTrue((boolean)TestWork.get(a,"deviceMode"));assertFalse((boolean)TestWork.get(a,"busy"));assertFalse(original.isClosed());JSONObject retained=decoded(device.open(before));assertRecord(retained);
            assertEquals("SYNTHETIC-PRIVATE-DRAFT",retained.getJSONObject("drafts").getJSONObject("journal:"+draftDay).getString("content"));
            VaultCrypto.Session candidate=(VaultCrypto.Session)TestWork.get(a,"pendingProtectionSession");assertNotNull(candidate);
            store.fail=false;AlertDialog retry=dialog();
            if(BuildConfig.DEBUG)enterNewPassword(retry);
            else TestWork.views(retry.getWindow().getDecorView(),EditText.class).get(0).setText(recovery);
            positive(a,retry);
            assertFalse((boolean)TestWork.get(a,"deviceMode"));assertFalse((boolean)TestWork.get(a,"busy"));assertTrue(original.isClosed());assertFalse(device.enabled());JSONObject rekeyed=decoded(VaultCrypto.open(store.read(),PASSWORD.toCharArray()));assertRecord(rekeyed);
            assertEquals("SYNTHETIC-PRIVATE-DRAFT",rekeyed.getJSONObject("drafts").getJSONObject("journal:"+draftDay).getString("content"));
            if(BuildConfig.DEBUG)assertTrue("Replaced failed candidate must be closed",candidate.isClosed());
            else assertRecord(decoded(VaultCrypto.recover(store.read(),recovery)));
        }finally{close(c);}
    }
}
