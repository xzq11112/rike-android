package app.rike.offline;

import android.app.*;
import android.net.Uri;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

/** Task regressions on synthetic data. Does not claim hardware UX or performance validation. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28) @LooperMode(LooperMode.Mode.PAUSED)
public class UxFlowTest {
    private static final String PASSWORD="synthetic-ux-vault-password";
    private static void set(MainActivity a,String name,Object value)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(a,value);}
    private static void call(MainActivity a,String name)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(a);}
    private static void click(View root,String label){View v=TestWork.named(root,label);assertNotNull(label,v);assertTrue(label,v.isEnabled());v.performClick();}
    private static void click(MainActivity a,String label){click(a.getWindow().getDecorView(),label);}
    private static List<EditText> inputs(MainActivity a){return TestWork.views(a.getWindow().getDecorView(),EditText.class);}
    private static String text(View v){StringBuilder b=new StringBuilder();if(v instanceof TextView)b.append(((TextView)v).getText()).append('\n');if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)b.append(text(((ViewGroup)v).getChildAt(i)));return b.toString();}
    private static JSONObject configured()throws Exception{
        JSONObject d=Records.empty();for(String name:new String[]{"Alpha","Beta","Gamma","Delta"})Records.upsert(d,"practiceType",new JSONObject().put("id",name).put("name",name));
        for(int n:new int[]{5,10,15,20,30,60})Records.upsert(d,"durationPreset",new JSONObject().put("id","m"+n).put("minutes",n));return d;
    }
    private static void open(MainActivity a,JSONObject d)throws Exception{
        VaultCrypto.Created key=VaultCrypto.create(PASSWORD.toCharArray());new VaultStore(a).write(VaultCrypto.encrypt(key.session,d.toString().getBytes(StandardCharsets.UTF_8)));
        set(a,"session",key.session);set(a,"data",d);call(a,"showApp");
    }
    private static void confirm(MainActivity a)throws Exception{ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();TestWork.drain(a);}
    @Test public void backReturnsThroughSettingsBackupButJournalIsARootPage()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();open(a,configured());VaultCrypto.Session key=(VaultCrypto.Session)TestWork.get(a,"session");
        TestWork.navigate(a,3);TestWork.navigate(a,4);assertEquals(4,TestWork.get(a,"tab"));a.onBackPressed();assertEquals(5,TestWork.get(a,"tab"));a.onBackPressed();assertEquals(3,TestWork.get(a,"tab"));assertSame(key,TestWork.get(a,"session"));
        TestWork.navigate(a,2);inputs(a).get(0).setText("SYNTHETIC-DRAFT");TestWork.flush(a);a.onBackPressed();assertNull(TestWork.get(a,"session"));assertTrue(key.isClosed());open(a,configured());key=(VaultCrypto.Session)TestWork.get(a,"session");
        TestWork.navigate(a,0);TestWork.named(a.getWindow().getDecorView(),"Alpha").performLongClick();a.onBackPressed();assertNull(TestWork.get(a,"managedEntity"));assertSame(key,TestWork.get(a,"session"));c.pause().stop().destroy();
    }
    @Test public void manualLockSuppressesBiometricAndWipesRetiredViews()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();open(a,configured());
        View old=a.getWindow().getDecorView().findViewById(android.R.id.content);TextView privateButton=(TextView)TestWork.named(old,"Alpha");VaultCrypto.Session key=(VaultCrypto.Session)TestWork.get(a,"session");
        Files.write(new File(a.getNoBackupFilesDir(),"biometric.key").toPath(),new byte[]{1});click(a,"锁定");TestWork.drain(a);
        assertTrue(key.isClosed());assertNull(TestWork.get(a,"session"));assertFalse((boolean)TestWork.get(a,"biometricAttempted"));assertFalse((boolean)TestWork.get(a,"busy"));assertEquals("",privateButton.getText().toString());assertNull(TestWork.get(a,"saveStatus"));assertNotNull(TestWork.named(a.getWindow().getDecorView(),"解锁"));
        c.pause().resume();TestWork.drain(a);assertTrue((boolean)TestWork.get(a,"biometricAttempted"));assertNull(TestWork.get(a,"session"));c.pause().stop().destroy();
    }
    @Test public void directDragCommitsOnceAndCancelledDragDoesNotWrite()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();open(a,configured());View root=(View)TestWork.get(a,"root");long revision=((JSONObject)TestWork.get(a,"data")).getLong("revision");
        assertEquals(View.GONE,TestWork.named(root,"删除Delta").getVisibility());TestWork.drag(a,"Delta","Alpha",true);
        JSONObject d=(JSONObject)TestWork.get(a,"data");assertSame(root,TestWork.get(a,"root"));assertEquals("Delta",d.getJSONArray("practiceTypes").getJSONObject(0).getString("id"));assertEquals(revision+1,d.getLong("revision"));assertEquals(View.VISIBLE,TestWork.named(root,"删除Delta").getVisibility());
        assertNull(TestWork.named(root,"上移 Delta"));assertNull(TestWork.named(root,"下移 Delta"));assertNull(TestWork.named(root,"管理练习类型"));
        byte[] before=new VaultStore(a).read();TestWork.drag(a,"Delta","Gamma",false);assertArrayEquals(before,new VaultStore(a).read());assertEquals("Delta",((JSONObject)TestWork.get(a,"data")).getJSONArray("practiceTypes").getJSONObject(0).getString("id"));
        a.onBackPressed();assertNull(TestWork.get(a,"managedEntity"));assertNotNull(TestWork.get(a,"session"));c.pause().stop().destroy();
    }
    @Test public void editingBadgeCanStartAnotherDragWithoutOpeningASecondPage()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();open(a,configured());View root=(View)TestWork.get(a,"root");
        TestWork.drag(a,"Delta","Alpha",true);TestWork.drag(a,"删除Delta","Gamma",true);assertSame(root,TestWork.get(a,"root"));assertEquals("Alpha",((JSONObject)TestWork.get(a,"data")).getJSONArray("practiceTypes").getJSONObject(0).getString("id"));assertEquals("Delta",((JSONObject)TestWork.get(a,"data")).getJSONArray("practiceTypes").getJSONObject(3).getString("id"));c.pause().stop().destroy();
    }
    @Test public void failedTypeDragPreservesCiphertextAndDraftAndCanRetry()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();JSONObject fixture=configured();fixture.getJSONObject("drafts").put("checkIn",new JSONObject().put("typeId","Beta").put("manual","17").put("note","SYNTHETIC-KEEP"));open(a,fixture);
        SlowStore store=new SlowStore(a);set(a,"store",store);byte[] before=store.read();store.slow=true;store.fail=true;store.release.countDown();TestWork.drag(a,"Delta","Alpha",true);
        assertArrayEquals(before,store.read());assertEquals("Alpha",((JSONObject)TestWork.get(a,"data")).getJSONArray("practiceTypes").getJSONObject(0).getString("id"));assertEquals("17",inputs(a).get(0).getText().toString());assertEquals("SYNTHETIC-KEEP",inputs(a).get(1).getText().toString());assertTrue(((Diagnostics)TestWork.get(a,"diagnostics")).report().contains("ORDER_COMMIT_FAILED"));
        store.fail=false;TestWork.drag(a,"Delta","Alpha",true);assertEquals("Delta",((JSONObject)TestWork.get(a,"data")).getJSONArray("practiceTypes").getJSONObject(0).getString("id"));c.pause().stop().destroy();
    }
    @Test public void backgroundDuringUnreleasedDragLocksWithoutSavingOrder()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();open(a,configured());TestWork.layout(a);byte[] before=new VaultStore(a).read();View source=TestWork.named(a.getWindow().getDecorView(),"Delta"),target=TestWork.named(a.getWindow().getDecorView(),"Alpha");int[] xy=new int[2];source.getLocationOnScreen(xy);TestWork.touch(source,MotionEvent.ACTION_DOWN,xy[0]+source.getWidth()/2f,xy[1]+source.getHeight()/2f);source.performLongClick();target.getLocationOnScreen(xy);TestWork.touch(source,MotionEvent.ACTION_MOVE,xy[0]+target.getWidth()/2f,xy[1]+target.getHeight()/2f);c.pause();TestWork.touch(source,MotionEvent.ACTION_UP,xy[0],xy[1]);TestWork.drain(a);assertNull(TestWork.get(a,"session"));assertNull(TestWork.get(a,"data"));assertEquals("",((TextView)source).getText().toString());assertArrayEquals(before,new VaultStore(a).read());c.stop().destroy();
    }
    @Test public void newTypeIsSelectedOnlyAfterSuccessfulPersistence()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();open(a,Records.empty());click(a,"添加练习类型");AlertDialog d=ShadowAlertDialog.getLatestAlertDialog();TestWork.views(d.getWindow().getDecorView(),EditText.class).get(0).setText("SYNTHETIC-FIRST-TYPE");confirm(a);
        String id=((JSONObject)TestWork.get(a,"data")).getJSONArray("practiceTypes").getJSONObject(0).getString("id");assertEquals(id,TestWork.draft(a,"checkIn").getString("typeId"));assertTrue(TestWork.named(a.getWindow().getDecorView(),"SYNTHETIC-FIRST-TYPE").isSelected());
        inputs(a).get(0).setText("17");click(a,"保存练习");TestWork.drain(a);assertEquals(17,((JSONObject)TestWork.get(a,"data")).getJSONArray("checkIns").getJSONObject(0).getInt("durationMinutes"));c.pause().stop().destroy();
    }
    @Test public void dayPickerNeverMovesPreviousDraftOrChangesExistingFirstTime()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();LocalDate today=LocalDate.now(),target=today.minusDays(1);JSONObject d=configured();
        d.getJSONArray("journals").put(new JSONObject().put("id","existing").put("journalDate",target.toString()).put("content","SYNTHETIC-TARGET").put("createdAt","2020-01-02T03:04:05Z"));open(a,d);TestWork.navigate(a,2);assertEquals(1,inputs(a).size());inputs(a).get(0).setText("SYNTHETIC-TODAY-DRAFT");
        click(a,"选择感悟日期");AlertDialog calendar=ShadowAlertDialog.getLatestAlertDialog();TestWork.chooseCalendarMonth(calendar,YearMonth.from(target));HeatmapView g=TestWork.views(calendar.getWindow().getDecorView(),HeatmapView.class).get(0);g.getAccessibilityNodeProvider().performAction(target.getDayOfMonth()-1,android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,null);
        assertEquals(target.toString(),TestWork.get(a,"journalDate"));assertEquals("SYNTHETIC-TARGET",inputs(a).get(0).getText().toString());assertNull(TestWork.named(a.getWindow().getDecorView(),"打开这一天"));assertNotNull(TestWork.named(a.getWindow().getDecorView(),"选择感悟日期"));assertEquals("SYNTHETIC-TODAY-DRAFT",TestWork.draft(a,"journal:"+today).getString("content"));
        inputs(a).get(0).setText("SYNTHETIC-EDITED-TARGET");click(a,"保存感悟");TestWork.drain(a);JSONObject result=(JSONObject)TestWork.get(a,"data");assertEquals(1,result.getJSONArray("journals").length());JSONObject row=result.getJSONArray("journals").getJSONObject(0);assertEquals(target.toString(),row.getString("journalDate"));assertEquals("existing",row.getString("id"));assertEquals("2020-01-02T03:04:05Z",row.getString("createdAt"));assertEquals("SYNTHETIC-TODAY-DRAFT",result.getJSONObject("drafts").getJSONObject("journal:"+today).getString("content"));c.pause().stop().destroy();
    }
    @Test public void websiteInputsAreVisibleAndSupplementaryDetailsCanCollapse()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();open(a,configured());TestWork.layout(a);View root=(View)TestWork.get(a,"root");ScrollView scroll=(ScrollView)TestWork.get(a,"contentScroll");
        assertNotSame(root,TestWork.named(root,"保存练习").getParent());assertEquals(View.VISIBLE,((View)inputs(a).get(0).getParent()).getVisibility());
        int visibleTargets=0;for(Button b:TestWork.views(scroll,Button.class))if(b.isShown()){visibleTargets++;assertTrue("touch target: "+b.getText(),b.getMeasuredHeight()>=Math.round(48*a.getResources().getDisplayMetrics().density));}assertTrue(visibleTargets>=10);
        click(a,"Alpha");click(a,"30 分钟");click(a,"30 分钟");assertFalse(TestWork.draft(a,"checkIn").has("preset"));
        click(a,"补记日期或添加备注");inputs(a).get(1).setText("SYNTHETIC-NOTE");TestWork.flush(a);TestWork.navigate(a,1);TestWork.navigate(a,0);assertEquals(View.VISIBLE,((View)inputs(a).get(1).getParent()).getVisibility());assertEquals("SYNTHETIC-NOTE",inputs(a).get(1).getText().toString());c.pause().stop().destroy();
    }
    private static class SlowStore extends VaultStore{
        boolean slow,fail;final CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        SlowStore(MainActivity a){super(a);}
        @Override public void write(byte[] bytes)throws IOException{if(slow){started.countDown();try{if(!release.await(30,TimeUnit.SECONDS))throw new IOException();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}if(fail)throw new IOException("SYNTHETIC-PRIVATE-PATH");}super.write(bytes);}
    }
    @Test public void slowSubmitShowsDisabledStateKeepsLockAvailableAndDoesNotDuplicate()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();open(a,configured());SlowStore s=new SlowStore(a);set(a,"store",s);s.slow=true;
        click(a,"Alpha");click(a,"20 分钟");Button submit=(Button)TestWork.named(a.getWindow().getDecorView(),"保存练习");submit.performClick();assertTrue(s.started.await(30,TimeUnit.SECONDS));
        assertFalse(submit.isEnabled());assertEquals("保存中…",submit.getText().toString());assertTrue(submit.getAlpha()<1f);assertFalse(TestWork.named(a.getWindow().getDecorView(),"记录").isEnabled());assertTrue(TestWork.named(a.getWindow().getDecorView(),"锁定").isEnabled());
        final boolean[] frame={false};new android.os.Handler(Looper.getMainLooper()).post(()->frame[0]=true);Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(frame[0]);for(int i=0;i<10;i++)submit.performClick();s.release.countDown();TestWork.drain(a);
        assertEquals(1,((JSONObject)TestWork.get(a,"data")).getJSONArray("checkIns").length());assertTrue(text(a.getWindow().getDecorView()).contains("今日一课，已记下"));assertTrue(TestWork.named(a.getWindow().getDecorView(),"保存练习").isEnabled());c.pause().stop().destroy();
    }
    @Test public void failedSubmitKeepsInputAndCiphertextAndCanRetry()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();open(a,configured());SlowStore s=new SlowStore(a);set(a,"store",s);byte[] before=s.read();s.slow=true;s.fail=true;s.release.countDown();click(a,"Alpha");click(a,"20 分钟");click(a,"补记日期或添加备注");inputs(a).get(1).setText("SYNTHETIC-PRIVATE-NOTE");click(a,"保存练习");TestWork.drain(a);
        assertArrayEquals(before,s.read());assertEquals(0,((JSONObject)TestWork.get(a,"data")).getJSONArray("checkIns").length());assertTrue(TestWork.named(a.getWindow().getDecorView(),"保存练习").isEnabled());assertEquals("SYNTHETIC-PRIVATE-NOTE",inputs(a).get(1).getText().toString());assertFalse(((Diagnostics)TestWork.get(a,"diagnostics")).report().contains("SYNTHETIC"));
        s.fail=false;click(a,"保存练习");TestWork.drain(a);assertEquals(1,((JSONObject)TestWork.get(a,"data")).getJSONArray("checkIns").length());c.pause().stop().destroy();
    }
    @Test public void rootBackDuringWriteLocksAndLateResultCannotRevealRecords()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();open(a,configured());SlowStore s=new SlowStore(a);set(a,"store",s);s.slow=true;click(a,"Alpha");click(a,"20 分钟");click(a,"保存练习");assertTrue(s.started.await(30,TimeUnit.SECONDS));a.onBackPressed();assertNull(TestWork.get(a,"session"));assertNull(TestWork.get(a,"data"));s.release.countDown();TestWork.drain(a);assertNull(TestWork.get(a,"session"));assertNull(TestWork.get(a,"data"));VaultCrypto.Opened reopened=VaultCrypto.open(s.read(),PASSWORD.toCharArray());assertEquals(1,new JSONObject(new String(reopened.plaintext,StandardCharsets.UTF_8)).getJSONArray("checkIns").length());Arrays.fill(reopened.plaintext,(byte)0);reopened.session.close();c.pause().stop().destroy();
    }
    @Test public void exportDirectVerifyRequiresPasswordWithoutAnotherPickerOrAnyReplacement()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();open(a,configured());TestWork.navigate(a,4);File file=new File(a.getCacheDir(),"synthetic-direct-export.rike");set(a,"pendingAction",103);set(a,"pendingUri",Uri.fromFile(file));call(a,"consumePending");TestWork.drain(a);byte[] before=new VaultStore(a).read();assertArrayEquals(before,Files.readAllBytes(file.toPath()));
        assertNotNull(TestWork.named(a.getWindow().getDecorView(),"验证这份备份"));click(a,"验证这份备份");assertNull(Shadows.shadowOf(a).getNextStartedActivity());AlertDialog d=ShadowAlertDialog.getLatestAlertDialog();TestWork.views(d.getWindow().getDecorView(),EditText.class).get(0).setText("wrong");confirm(a);assertArrayEquals(before,new VaultStore(a).read());assertTrue(d.isShowing());
        TestWork.views(d.getWindow().getDecorView(),EditText.class).get(0).setText(PASSWORD);confirm(a);assertArrayEquals(before,new VaultStore(a).read());assertTrue(text(a.getWindow().getDecorView()).contains("已成功解密并校验"));assertEquals(4,((JSONObject)TestWork.get(a,"data")).getJSONArray("practiceTypes").length());c.pause().stop().destroy();
    }
    private static File backup(MainActivity a,JSONObject d)throws Exception{VaultCrypto.Created key=VaultCrypto.create(PASSWORD.toCharArray());File file=new File(a.getCacheDir(),"synthetic-restore.rike");try{Files.write(file.toPath(),VaultCrypto.encrypt(key.session,d.toString().getBytes(StandardCharsets.UTF_8)));return file;}finally{key.session.close();}}
    @Test public void freshRestorePreviewsBeforeCreatingAndCancellationLeavesNoVault()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();File backup=backup(a,configured());set(a,"pendingAction",102);set(a,"pendingUri",Uri.fromFile(backup));call(a,"showLocked");inputs(a).get(0).setText(PASSWORD);click(a,"恢复资料库");TestWork.drain(a);
        assertFalse(new VaultStore(a).exists());VaultCrypto.Session candidate=(VaultCrypto.Session)TestWork.get(a,"freshRestoreSession");assertNotNull(candidate);ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(new VaultStore(a).exists());assertTrue(candidate.isClosed());assertNull(TestWork.get(a,"freshRestoreSession"));assertNull(TestWork.get(a,"pendingUri"));c.pause().stop().destroy();
    }
    @Test public void freshRestoreCreatesOnlyAfterConfirmationAndKeepsSettings()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();JSONObject d=configured();d.put("unknownSyntheticMetadata",new JSONObject().put("flag",true));File backup=backup(a,d);set(a,"pendingAction",102);set(a,"pendingUri",Uri.fromFile(backup));call(a,"showLocked");inputs(a).get(0).setText(PASSWORD);click(a,"恢复资料库");TestWork.drain(a);assertFalse(new VaultStore(a).exists());confirm(a);
        assertTrue(new VaultStore(a).exists());JSONObject restored=(JSONObject)TestWork.get(a,"data");assertEquals(4,restored.getJSONArray("practiceTypes").length());assertEquals(6,restored.getJSONArray("durationPresets").length());assertTrue(restored.getJSONObject("unknownSyntheticMetadata").getBoolean("flag"));VaultCrypto.Opened reopened=VaultCrypto.open(new VaultStore(a).read(),PASSWORD.toCharArray());Arrays.fill(reopened.plaintext,(byte)0);reopened.session.close();c.pause().stop().destroy();
    }
    @Test public void selectedPasswordKeyboardPreferencePersistsWithoutStoringSecret()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();click(a,"使用字符键盘");open(a,configured());click(a,"锁定");assertNotNull(TestWork.named(a.getWindow().getDecorView(),"使用数字键盘"));int inputType=inputs(a).get(0).getInputType();assertEquals(android.text.InputType.TYPE_CLASS_TEXT,inputType&android.text.InputType.TYPE_MASK_CLASS);assertEquals("",inputs(a).get(0).getText().toString());assertFalse(((Diagnostics)TestWork.get(a,"diagnostics")).report().contains(PASSWORD));c.pause().stop().destroy();
    }
}
