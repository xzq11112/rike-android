package app.rike.offline;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowToast;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28) @LooperMode(LooperMode.Mode.PAUSED)
public class DiagnosticsTest {
    private static Object get(Object a,String name)throws Exception{Field f=a.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(a);}
    private static void set(Object a,String name,Object value)throws Exception{Field f=a.getClass().getDeclaredField(name);f.setAccessible(true);f.set(a,value);}
    private static void call(Object a,String name)throws Exception{Method m=a.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(a);}
    private static Button button(View v,String label){if(v instanceof Button&&(label.contentEquals(((Button)v).getText())||label.contentEquals(v.getContentDescription()==null?"":v.getContentDescription())))return (Button)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){Button b=button(((ViewGroup)v).getChildAt(i),label);if(b!=null)return b;}return null;}

    @Test public void boundedExpiredAndClearedWithoutTimestamps() {
        AtomicLong clock=new AtomicLong(12345);Diagnostics d=new Diagnostics(clock::get);
        d.record(Diagnostics.Code.BACKUP_READ_FAILED);
        for(int i=0;i<Diagnostics.LIMIT;i++)d.record(Diagnostics.Code.DATA_INVALID);
        String report=d.report();
        assertFalse(report.contains("BACKUP_READ_FAILED"));assertFalse(report.contains("12345"));
        assertEquals(Diagnostics.LIMIT,report.split("DATA_INVALID",-1).length-1);
        clock.addAndGet(Diagnostics.RETENTION_MS);assertTrue(d.report().contains("无诊断记录"));
        d.record(Diagnostics.Code.STORAGE_IO);d.clear();assertFalse(d.report().contains("STORAGE_IO"));
    }

    @Test public void exceptionPayloadNeverEntersReportToastOrLogcat()throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();
        String secret="SYNTHETIC-PRIVATE-BODY-password-content://secret/path";
        Method fail=MainActivity.class.getDeclaredMethod("fail",Exception.class);fail.setAccessible(true);
        for(Exception e:new Exception[]{new IOException(secret),new IllegalArgumentException(secret),new org.json.JSONException(secret),new java.security.GeneralSecurityException(secret)}){
            fail.invoke(a,e);assertFalse(ShadowToast.getTextOfLatestToast().contains(secret));
        }
        String report=((Diagnostics)get(a,"diagnostics")).report();assertFalse(report.contains(secret));
        assertTrue(report.contains("STORAGE_IO"));assertTrue(report.contains("AUTH_OR_FILE_INVALID"));
        for(ShadowLog.LogItem item:ShadowLog.getLogs())assertFalse(String.valueOf(item.msg).contains(secret));
        c.pause().stop().destroy();
    }

    @Test public void reportExportRequiresConfirmationPickerAndUnlockAndDoesNotTouchVault()throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();
        VaultCrypto.Created keys=VaultCrypto.create("synthetic-diagnostics-password".toCharArray());
        JSONObject data=Records.empty();data.getJSONObject("drafts").put("secret","SYNTHETIC-PRIVATE-DRAFT");
        VaultStore store=new VaultStore(a);store.write(VaultCrypto.encrypt(keys.session,data.toString().getBytes(StandardCharsets.UTF_8)));
        byte[] before=store.read();set(a,"session",keys.session);set(a,"data",data);set(a,"tab",4);call(a,"showApp");
        Diagnostics diagnostics=(Diagnostics)get(a,"diagnostics");diagnostics.record(Diagnostics.Code.STORAGE_IO);
        String expected=diagnostics.report();button(a.getWindow().getDecorView(),"查看本地诊断").performClick();
        AlertDialog preview=ShadowAlertDialog.getLatestAlertDialog();preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(get(a,"diagnosticExport"));
        AlertDialog confirmation=ShadowAlertDialog.getLatestAlertDialog();assertNotSame(preview,confirmation);
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(expected,get(a,"diagnosticExport"));
        File file=new File(a.getCacheDir(),"report.txt");
        a.onActivityResult(105,android.app.Activity.RESULT_OK,new Intent().setData(Uri.fromFile(file)));
        assertNull(get(a,"session"));call(a,"consumePending");assertFalse(file.exists());
        VaultCrypto.Opened opened=VaultCrypto.open(store.read(),"synthetic-diagnostics-password".toCharArray());
        set(a,"session",opened.session);set(a,"data",data);call(a,"consumePending");TestWork.drain(a);
        assertEquals(expected,new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
        assertFalse(expected.contains("SYNTHETIC-PRIVATE-DRAFT"));assertNull(get(a,"diagnosticExport"));assertArrayEquals(before,store.read());
        c.pause().stop().destroy();assertTrue(diagnostics.report().contains("无诊断记录"));
    }
}
