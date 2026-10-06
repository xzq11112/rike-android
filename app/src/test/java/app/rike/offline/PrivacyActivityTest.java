package app.rike.offline;
import android.Manifest;
import android.content.pm.PackageManager;
import android.view.*;
import android.widget.TextView;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import java.lang.reflect.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class PrivacyActivityTest {
    private static void set(Object target,String field,Object value)throws Exception{Field f=target.getClass().getDeclaredField(field);f.setAccessible(true);f.set(target,value);}
    private static Object get(Object target,String field)throws Exception{Field f=target.getClass().getDeclaredField(field);f.setAccessible(true);return f.get(target);}
    private static void call(Object target,String method)throws Exception{Method m=target.getClass().getDeclaredMethod(method);m.setAccessible(true);m.invoke(target);}
    private static String text(View v){StringBuilder b=new StringBuilder();if(v instanceof TextView)b.append(((TextView)v).getText());if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)b.append(text(((ViewGroup)v).getChildAt(i)));return b.toString();}
    @Test public void backgroundDestroysSessionAndRemovesPrivateScreen()throws Exception{
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=controller.get();
        VaultCrypto.Created c=VaultCrypto.create("synthetic-test-password".toCharArray());JSONObject d=Records.empty();
        d.getJSONArray("journals").put(new JSONObject().put("id","test").put("journalDate",java.time.LocalDate.now().toString()).put("content","synthetic-private-journal"));
        set(a,"session",c.session);set(a,"data",d);set(a,"tab",2);call(a,"showApp");
        assertTrue(text(a.getWindow().getDecorView()).contains("synthetic-private-journal"));
        controller.pause();assertNull(get(a,"session"));assertNull(get(a,"data"));assertTrue(c.session.isClosed());
        assertFalse(text(a.getWindow().getDecorView()).contains("synthetic-private-journal"));controller.stop().destroy();
    }
    @Test public void noInternetNoBackupAndSecureWindow(){
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=controller.get();
        assertEquals(PackageManager.PERMISSION_DENIED,a.getPackageManager().checkPermission(Manifest.permission.INTERNET,a.getPackageName()));
        assertTrue((a.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);
        assertFalse((a.getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)!=0);
        controller.pause().stop().destroy();
    }
    @Test public void diskContainsCiphertextOnlyAndRoundTrips()throws Exception{
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();
        VaultStore store=new VaultStore(controller.get());VaultCrypto.Created c=VaultCrypto.create("synthetic-test-password".toCharArray());
        byte[] plain="synthetic-private-draft".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        store.write(VaultCrypto.encrypt(c.session,plain));
        assertFalse(new String(store.read(),java.nio.charset.StandardCharsets.UTF_8).contains("synthetic-private-draft"));
        VaultCrypto.Opened o=VaultCrypto.recover(store.read(),c.recoveryCode);assertArrayEquals(plain,o.plaintext);
        c.session.close();o.session.close();controller.pause().stop().destroy();
    }

    private static android.widget.Button button(View view,String label){
        if(view instanceof android.widget.Button && (((android.widget.Button)view).getText().toString().equals(label)||label.contentEquals(view.getContentDescription()==null?"":view.getContentDescription())))return (android.widget.Button)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){android.widget.Button b=button(((ViewGroup)view).getChildAt(i),label);if(b!=null)return b;}
        return null;
    }
    private static android.widget.EditText firstInput(View view){
        if(view instanceof android.widget.EditText)return (android.widget.EditText)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){android.widget.EditText e=firstInput(((ViewGroup)view).getChildAt(i));if(e!=null)return e;}
        return null;
    }
    @Test public void presetsToggleAndManualMinutesStayIndependent()throws Exception{
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=controller.get();
        VaultCrypto.Created c=VaultCrypto.create("synthetic-test-password".toCharArray());JSONObject d=Records.empty();
        d.getJSONArray("practiceTypes").put(new JSONObject().put("id","t").put("name","test type"));
        d.getJSONArray("durationPresets").put(new JSONObject().put("id","m").put("minutes",30));
        set(a,"session",c.session);set(a,"data",d);call(a,"showApp");
        View root=a.getWindow().getDecorView();
        button(root,"test type").performClick();
        android.widget.EditText input=firstInput(root);assertEquals(View.VISIBLE,((View)input.getParent()).getVisibility());
        input.setText("30");
        JSONObject draft=TestWork.draft(a,"checkIn");
        assertFalse(draft.has("preset"));assertEquals("30",draft.getString("manual"));
        button(root,"30 分钟").performClick();assertEquals("",input.getText().toString());
        draft=TestWork.draft(a,"checkIn");
        assertEquals(30,draft.getInt("preset"));
        button(root,"30 分钟").performClick();
        assertFalse(TestWork.draft(a,"checkIn").has("preset"));
        button(root,"30 分钟").performClick();button(root,"保存练习").performClick();TestWork.drain(a);
        JSONObject record=((JSONObject)get(a,"data")).getJSONArray("checkIns").getJSONObject(0);
        assertEquals("test type",record.getString("practiceTypeName"));assertEquals(30,record.getInt("durationMinutes"));
        controller.pause().stop().destroy();
    }
    @Test public void selectedImportSurvivesMandatoryLockAndResumesAfterUnlock()throws Exception{
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=controller.get();
        VaultCrypto.Created c=VaultCrypto.create("synthetic-test-password".toCharArray());JSONObject d=Records.empty();
        VaultStore store=new VaultStore(a);store.write(VaultCrypto.encrypt(c.session,d.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        set(a,"session",c.session);set(a,"data",d);call(a,"showApp");controller.pause();
        JSONObject imported=Records.empty();imported.getJSONArray("dreams").put(new JSONObject().put("id","test").put("sleepDate","2026-09-16").put("sleepPeriod","nap").put("content","synthetic-import"));
        java.io.File f=new java.io.File(a.getCacheDir(),"synthetic-import.json");
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(f)){out.write(imported.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        android.net.Uri uri=android.net.Uri.fromFile(f);
        a.onActivityResult(101,android.app.Activity.RESULT_OK,new android.content.Intent().setData(uri));
        assertNull(get(a,"session"));assertEquals(uri,get(a,"pendingUri"));
        controller.resume();
        VaultCrypto.Opened opened=VaultCrypto.open(store.read(),"synthetic-test-password".toCharArray());
        set(a,"session",opened.session);set(a,"data",d);call(a,"showApp");call(a,"consumePending");TestWork.drain(a);
        android.app.AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        assertTrue((dialog.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();TestWork.drain(a);
        assertEquals(1,((JSONObject)get(a,"data")).getJSONArray("dreams").length());assertNull(get(a,"pendingUri"));
        VaultCrypto.Opened disk=VaultCrypto.recover(store.read(),c.recoveryCode);
        assertEquals(1,new JSONObject(new String(disk.plaintext,java.nio.charset.StandardCharsets.UTF_8)).getJSONArray("dreams").length());
        disk.session.close();controller.pause().stop().destroy();f.delete();
    }
}
