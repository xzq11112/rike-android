package app.rike.offline;

import android.app.AlertDialog;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

/** Synthetic end-to-end local workflows. No device biometrics or real user data. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28) @LooperMode(LooperMode.Mode.PAUSED)
public class DeliveryFlowTest {
    private static final String PASSWORD="synthetic-delivery-password";
    private static Object get(Object a,String name)throws Exception{Field f=a.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(a);}
    private static void set(Object a,String name,Object value)throws Exception{Field f=a.getClass().getDeclaredField(name);f.setAccessible(true);f.set(a,value);}
    private static void call(Object a,String name)throws Exception{Method m=a.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(a);}
    private static List<EditText> inputs(View v){List<EditText> all=new ArrayList<>();if(v instanceof EditText)all.add((EditText)v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)all.addAll(inputs(((ViewGroup)v).getChildAt(i)));return all;}
    private static Button button(View v,String label){if(v instanceof Button&&(label.contentEquals(((Button)v).getText())||label.contentEquals(v.getContentDescription()==null?"":v.getContentDescription())))return (Button)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){Button b=button(((ViewGroup)v).getChildAt(i),label);if(b!=null)return b;}return null;}
    private static void settle(MainActivity a)throws Exception{TestWork.drain(a);}
    private static void unlock(MainActivity a,JSONObject d)throws Exception{
        VaultCrypto.Created created=VaultCrypto.create(PASSWORD.toCharArray());
        new VaultStore(a).write(VaultCrypto.encrypt(created.session,d.toString().getBytes(StandardCharsets.UTF_8)));
        set(a,"session",created.session);set(a,"data",d);call(a,"showApp");
    }
    private static JSONObject withDream()throws Exception{
        JSONObject d=Records.empty();Records.upsert(d,"dream",new JSONObject().put("id","dream").put("sleepDate","2026-09-17").put("sleepPeriod","nap").put("content","synthetic private dream"));return d;
    }
    private static void openBackup(MainActivity a,File file,int action)throws Exception{
        set(a,"pendingAction",action);set(a,"pendingUri",Uri.fromFile(file));call(a,"consumePending");
    }
    @Test public void firstSetupCreatesVaultWithoutRecoveryCeremonyInDebug()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();
        List<EditText> fields=inputs(a.getWindow().getDecorView());assertEquals(2,fields.size());
        fields.get(0).setText(PASSWORD);fields.get(1).setText(PASSWORD);
        button(a.getWindow().getDecorView(),"建立资料库").performClick();settle(a);
        if(BuildConfig.DEBUG){
            assertNotNull(get(a,"data"));assertTrue(new VaultStore(a).exists());
            assertNotNull(button(a.getWindow().getDecorView(),"保存练习"));
        }else{
            assertNull(get(a,"data"));assertFalse(new VaultStore(a).exists());
            assertNotNull(button(a.getWindow().getDecorView(),"我已离线保存，继续核对"));
            button(a.getWindow().getDecorView(),"我已离线保存，继续核对").performClick();
            assertNotNull(button(a.getWindow().getDecorView(),"我已离线保存，建立资料库"));
        }
        c.pause().stop().destroy();
    }
    @Test public void filePickerReturnLocksEvenWithoutPause()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();unlock(a,withDream());
        VaultCrypto.Session old=(VaultCrypto.Session)get(a,"session");
        a.onActivityResult(101,Activity.RESULT_OK,new Intent().setData(Uri.parse("content://synthetic/file")));
        assertTrue(old.isClosed());assertNull(get(a,"session"));assertNull(get(a,"data"));
        assertEquals(Uri.parse("content://synthetic/file"),get(a,"pendingUri"));c.pause().stop().destroy();
    }
    @Test public void journalUiSavesSameDayWithoutDuplicate()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();unlock(a,Records.empty());
        set(a,"tab",2);call(a,"showApp");
        inputs(a.getWindow().getDecorView()).get(0).setText("first journal");button(a.getWindow().getDecorView(),"保存感悟").performClick();settle(a);
        String first=((JSONObject)get(a,"data")).getJSONArray("journals").getJSONObject(0).getString("createdAt");
        TestWork.named(a.getWindow().getDecorView(),"编辑这篇日记").performClick();inputs(a.getWindow().getDecorView()).get(0).setText("edited journal");button(a.getWindow().getDecorView(),"保存感悟").performClick();settle(a);
        JSONObject d=(JSONObject)get(a,"data");assertEquals(1,d.getJSONArray("journals").length());
        assertEquals("edited journal",d.getJSONArray("journals").getJSONObject(0).getString("content"));assertEquals(first,d.getJSONArray("journals").getJSONObject(0).getString("createdAt"));c.pause().stop().destroy();
    }
    @Test public void exportVerifyRestoreAndWrongPasswordPreserveData()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();unlock(a,withDream());
        File backup=new File(a.getCacheDir(),"synthetic.rike");openBackup(a,backup,103);settle(a);
        byte[] bytes=Files.readAllBytes(backup.toPath());assertArrayEquals(new VaultStore(a).read(),bytes);
        assertFalse(new String(bytes,StandardCharsets.UTF_8).contains("synthetic private dream"));
        unlock(a,Records.empty());byte[] unchanged=new VaultStore(a).read();
        openBackup(a,backup,104);AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
        inputs(dialog.getWindow().getDecorView()).get(0).setText(PASSWORD);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle(a);
        assertArrayEquals(unchanged,new VaultStore(a).read());ShadowAlertDialog.getLatestAlertDialog().dismiss();
        openBackup(a,backup,102);dialog=ShadowAlertDialog.getLatestAlertDialog();
        inputs(dialog.getWindow().getDecorView()).get(0).setText("wrong-password");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle(a);
        assertArrayEquals(unchanged,new VaultStore(a).read());assertTrue(dialog.isShowing());
        inputs(dialog.getWindow().getDecorView()).get(0).setText(PASSWORD);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle(a);
        // Reading alone is not permission to replace the vault.
        assertArrayEquals(unchanged,new VaultStore(a).read());
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        // AlertDialog posts its standard button callback to the main Looper.
        // PAUSED mode must dispatch it before inspecting the persisted result.
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();settle(a);
        assertEquals(1,((JSONObject)get(a,"data")).getJSONArray("dreams").length());
        VaultCrypto.Opened opened=VaultCrypto.open(new VaultStore(a).read(),PASSWORD.toCharArray());
        assertEquals(1,new JSONObject(new String(opened.plaintext,StandardCharsets.UTF_8)).getJSONArray("dreams").length());opened.session.close();
        c.pause().stop().destroy();
    }
    @Test public void cancellingPasswordChangeKeepsOldPassword()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();unlock(a,withDream());
        byte[] before=new VaultStore(a).read();call(a,"changePassword");AlertDialog d=ShadowAlertDialog.getLatestAlertDialog();
        for(EditText e:inputs(d.getWindow().getDecorView()))e.setText("new-synthetic-password");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();settle(a);
        assertArrayEquals(before,new VaultStore(a).read());assertNotNull(get(a,"session"));assertFalse((boolean)get(a,"busy"));c.pause().stop().destroy();
    }
    @Test public void pendingPasswordUnlockCannotUnlockAfterPause()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();unlock(a,withDream());call(a,"lock");
        inputs(a.getWindow().getDecorView()).get(0).setText(PASSWORD);Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(550));
        c.pause();settle(a);assertNull(get(a,"session"));assertNull(get(a,"data"));c.stop().destroy();
    }
}
