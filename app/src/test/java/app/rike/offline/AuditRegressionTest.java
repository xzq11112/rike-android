package app.rike.offline;

import android.app.AlertDialog;
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

@RunWith(RobolectricTestRunner.class) @Config(sdk=28) @LooperMode(LooperMode.Mode.PAUSED)
public class AuditRegressionTest {
    private static final String PASSWORD="synthetic-audit";
    private static void set(MainActivity a,String n,Object v)throws Exception{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);f.set(a,v);}
    private static void call(MainActivity a,String n)throws Exception{Method m=MainActivity.class.getDeclaredMethod(n);m.setAccessible(true);m.invoke(a);}
    private static void click(MainActivity a,String n){View v=TestWork.named(a.getWindow().getDecorView(),n);assertNotNull(n,v);assertTrue(n,v.isEnabled());v.performClick();}
    private static List<EditText> inputs(View v){return TestWork.views(v,EditText.class);}
    private static JSONObject data(MainActivity a)throws Exception{return (JSONObject)TestWork.get(a,"data");}
    private static JSONObject configured()throws Exception{JSONObject d=Records.empty();Records.upsert(d,"practiceType",new JSONObject().put("id","t").put("name","Synthetic"));Records.upsert(d,"durationPreset",new JSONObject().put("id","m").put("minutes",20));return d;}
    private static void open(MainActivity a,JSONObject d)throws Exception{VaultCrypto.Created c=VaultCrypto.create(PASSWORD.toCharArray());new VaultStore(a).write(VaultCrypto.encrypt(c.session,d.toString().getBytes(StandardCharsets.UTF_8)));set(a,"session",c.session);set(a,"data",d);call(a,"showApp");}
    private static JSONObject disk(MainActivity a)throws Exception{VaultCrypto.Opened o=VaultCrypto.open(new VaultStore(a).read(),PASSWORD.toCharArray());try{return new JSONObject(new String(o.plaintext,StandardCharsets.UTF_8));}finally{Arrays.fill(o.plaintext,(byte)0);o.session.close();}}
    private static void confirm(MainActivity a)throws Exception{ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();TestWork.drain(a);}
    private static class SlowStore extends VaultStore {
        volatile boolean slow,fail;final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        SlowStore(MainActivity a){super(a);}
        @Override public void write(byte[] b)throws IOException{if(slow){entered.countDown();try{if(!release.await(20,TimeUnit.SECONDS))throw new IOException();}catch(InterruptedException e){throw new IOException(e);}}if(fail)throw new IOException();super.write(b);}
    }
    @Test public void recreatedActivityReadsAfterOldWriteAndFlushOnSameQueue()throws Exception{
        ActivityController<MainActivity> c1=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c1.get();open(a,configured());SlowStore store=new SlowStore(a);set(a,"store",store);store.slow=true;
        click(a,"Synthetic");click(a,"20 分钟");click(a,"保存练习");assertTrue(store.entered.await(10,TimeUnit.SECONDS));c1.pause().stop().destroy();
        ActivityController<MainActivity> c2=Robolectric.buildActivity(MainActivity.class).setup();MainActivity b=c2.get();assertSame(TestWork.get(a,"crypto"),TestWork.get(b,"crypto"));
        Future<JSONObject> read=((ExecutorService)TestWork.get(b,"crypto")).submit(()->disk(b));
        try{read.get(100,TimeUnit.MILLISECONDS);fail("read crossed write barrier");}catch(TimeoutException expected){}
        store.release.countDown();assertEquals(1,read.get(15,TimeUnit.SECONDS).getJSONArray("checkIns").length());TestWork.drain(b);
        inputs(b.getWindow().getDecorView()).get(0).setText(PASSWORD);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(550));TestWork.drain(b);assertEquals(1,data(b).getJSONArray("checkIns").length());
        click(b,"Synthetic");click(b,"20 分钟");click(b,"保存练习");TestWork.drain(b);assertEquals(2,disk(b).getJSONArray("checkIns").length());assertNull(TestWork.get(a,"session"));c2.pause().stop().destroy();
    }
    @Test public void aSecondActiveActivityRevokesOldUiAndPreservesStagedDraft()throws Exception{
        ActivityController<MainActivity> c1=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c1.get();open(a,configured());click(a,"补记日期或添加备注");inputs(a.getWindow().getDecorView()).get(1).setText("SYNTHETIC-PENDING");
        ActivityController<MainActivity> c2=Robolectric.buildActivity(MainActivity.class).setup();MainActivity b=c2.get();assertNull(TestWork.get(a,"session"));TestWork.drain(b);assertEquals("SYNTHETIC-PENDING",disk(b).getJSONObject("drafts").getJSONObject("checkIn").getString("note"));c1.pause().stop().destroy();c2.pause().stop().destroy();
    }
    @Test public void freshRestoreCannotCancelAfterCommitAndBackgroundStaysLocked()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();JSONObject d=configured();VaultCrypto.Created key=VaultCrypto.create(PASSWORD.toCharArray());byte[] file=VaultCrypto.encrypt(key.session,d.toString().getBytes(StandardCharsets.UTF_8));key.session.close();VaultCrypto.Opened opened=VaultCrypto.open(file,PASSWORD.toCharArray());Arrays.fill(opened.plaintext,(byte)0);
        Method offer=MainActivity.class.getDeclaredMethod("offerFreshRestore",VaultCrypto.Opened.class,JSONObject.class,RecordIndex.class,long.class,boolean.class);offer.setAccessible(true);offer.invoke(a,opened,d,new RecordIndex(d,LocalDate.now()),TestWork.get(a,"generation"),false);
        SlowStore store=new SlowStore(a);store.slow=true;set(a,"store",store);AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(store.entered.await(10,TimeUnit.SECONDS));assertFalse(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled());dialog.onBackPressed();assertTrue(dialog.isShowing());assertSame(opened.session,TestWork.get(a,"freshRestoreSession"));
        c.pause();assertTrue(opened.session.isClosed());assertNull(TestWork.get(a,"session"));store.release.countDown();TestWork.drain(a);assertTrue(store.exists());assertEquals(1,disk(a).getJSONArray("practiceTypes").length());assertNull(TestWork.get(a,"data"));c.stop().destroy();
    }
    private static Clock day(String day,String zone){return Clock.fixed(LocalDate.parse(day).atTime(23,59).atZone(ZoneId.of(zone)).toInstant(),ZoneId.of(zone));}
    @Test public void todayDraftFollowsSaveDayAcrossMidnightAndManualDraftDoesNot()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();set(a,"clock",day("2026-12-31","Asia/Shanghai"));open(a,configured());click(a,"Synthetic");click(a,"20 分钟");assertEquals("today",TestWork.draft(a,"checkIn").getString("dateMode"));set(a,"clock",day("2027-01-01","Asia/Shanghai"));click(a,"保存练习");TestWork.drain(a);assertEquals("2027-01-01",data(a).getJSONArray("checkIns").getJSONObject(0).getString("practiceDate"));
        JSONObject manual=new JSONObject().put("typeId","t").put("preset",20).put("date","2026-12-29").put("dateMode","manual");VaultWriter writer=(VaultWriter)TestWork.get(a,"writer");writer.stage("checkIn",manual);call(a,"showApp");set(a,"clock",day("2027-01-02","America/Los_Angeles"));click(a,"保存练习");TestWork.drain(a);assertEquals("2026-12-29",data(a).getJSONArray("checkIns").getJSONObject(1).getString("practiceDate"));c.pause().stop().destroy();
    }
    @Test public void legacyDraftNeedsExplicitDateChoiceWithoutLosingText()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();JSONObject d=configured();d.getJSONObject("drafts").put("checkIn",new JSONObject().put("typeId","t").put("preset",20).put("date","2020-01-02").put("note","SYNTHETIC-LEGACY"));set(a,"clock",day("2026-10-05","Asia/Shanghai"));open(a,d);click(a,"保存练习");TestWork.drain(a);assertEquals(0,data(a).getJSONArray("checkIns").length());assertEquals("SYNTHETIC-LEGACY",inputs(a.getWindow().getDecorView()).get(1).getText().toString());click(a,"继续补记 2020-01-02");click(a,"保存练习");TestWork.drain(a);assertEquals("2020-01-02",data(a).getJSONArray("checkIns").getJSONObject(0).getString("practiceDate"));c.pause().stop().destroy();
    }
    @Test public void savingJournalKeepsEditorDateIndependentOfListFilter()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();set(a,"clock",day("2026-10-05","Asia/Shanghai"));open(a,configured());set(a,"tab",2);set(a,"journalFilter","2026-10-03");set(a,"journalDate","2026-10-04");set(a,"journalEditing",true);call(a,"showApp");inputs(a.getWindow().getDecorView()).get(0).setText("SYNTHETIC-B");click(a,"保存感悟");TestWork.drain(a);assertEquals("2026-10-03",TestWork.get(a,"journalFilter"));assertEquals("2026-10-04",TestWork.get(a,"journalDate"));assertNotNull(Records.journal(data(a),"2026-10-04"));assertEquals(0,TestWork.get(a,"journalPage"));c.pause().stop().destroy();
    }
    @Test public void draftListFindsOldAndEmptyDraftsAndDiscardCannotBeResurrected()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();JSONObject d=configured();Records.upsert(d,"journal",new JSONObject().put("id","j").put("journalDate","2020-01-01").put("content","SYNTHETIC-COMMITTED").put("createdAt","2020-01-02T03:04:05Z"));d.getJSONObject("drafts").put("journal:2020-01-01",new JSONObject().put("content","SYNTHETIC-EDIT")).put("journal:2020-01-02",new JSONObject().put("content",""));open(a,d);TestWork.navigate(a,2);click(a,"未完成草稿 2");AlertDialog list=ShadowAlertDialog.getLatestAlertDialog();assertEquals("2020-01-02",list.getListView().getAdapter().getItem(0));list.getListView().performItemClick(null,1,1);Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals("SYNTHETIC-EDIT",inputs(a.getWindow().getDecorView()).get(0).getText().toString());inputs(a.getWindow().getDecorView()).get(0).setText("SYNTHETIC-LATEST");click(a,"放弃未提交修改");confirm(a);TestWork.flush(a);assertFalse(data(a).getJSONObject("drafts").has("journal:2020-01-01"));assertEquals("SYNTHETIC-COMMITTED",inputs(a.getWindow().getDecorView()).get(0).getText().toString());assertEquals("2020-01-02T03:04:05Z",Records.journal(data(a),"2020-01-01").getString("createdAt"));assertTrue(disk(a).getJSONObject("drafts").has("journal:2020-01-02"));c.pause().stop().destroy();
    }
    @Test public void failedDraftCanRetryWithoutTypingOrCreatingFormalRecords()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();open(a,configured());SlowStore store=new SlowStore(a);set(a,"store",store);store.fail=true;byte[] original=store.read();click(a,"补记日期或添加备注");inputs(a.getWindow().getDecorView()).get(1).setText("SYNTHETIC-RETRY");TestWork.flush(a);assertArrayEquals(original,store.read());Button retry=(Button)TestWork.named(a.getWindow().getDecorView(),"重试草稿保存");assertEquals(View.VISIBLE,retry.getVisibility());store.fail=false;retry.performClick();TestWork.drain(a);assertEquals("SYNTHETIC-RETRY",disk(a).getJSONObject("drafts").getJSONObject("checkIn").getString("note"));assertEquals(0,data(a).getJSONArray("checkIns").length());assertEquals(View.GONE,retry.getVisibility());c.pause().stop().destroy();
    }
    @Test public void editCheckInKeepsIdFirstTimeAndDeletedTypeAndRefreshesStats()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();JSONObject d=configured();Records.upsert(d,"checkIn",new JSONObject().put("id","c").put("practiceTypeId","deleted").put("practiceTypeName","SYNTHETIC-OLD-TYPE").put("practiceDate",LocalDate.now().toString()).put("durationMinutes",20).put("note","original").put("createdAt","2020-01-02T03:04:05Z"));open(a,d);TestWork.navigate(a,1);Method editMethod=MainActivity.class.getDeclaredMethod("editCheckIn",JSONObject.class);editMethod.setAccessible(true);editMethod.invoke(a,data(a).getJSONArray("checkIns").getJSONObject(0));AlertDialog edit=ShadowAlertDialog.getLatestAlertDialog();List<EditText> fields=inputs(edit.getWindow().getDecorView());fields.get(0).setText("35");fields.get(1).setText("SYNTHETIC-EDITED");confirm(a);JSONObject row=data(a).getJSONArray("checkIns").getJSONObject(0);assertEquals("c",row.getString("id"));assertEquals("2020-01-02T03:04:05Z",row.getString("createdAt"));assertEquals("SYNTHETIC-OLD-TYPE",row.getString("practiceTypeName"));assertEquals(35,row.getInt("durationMinutes"));assertTrue(row.has("updatedAt"));TestWork.navigate(a,3);assertEquals(35,new RecordIndex(data(a),LocalDate.now()).stats.totalMinutes);assertEquals(35,disk(a).getJSONArray("checkIns").getJSONObject(0).getInt("durationMinutes"));c.pause().stop().destroy();
    }
    @Test public void capacityRejectsDraftAndRecordWhileBackupAndOriginalCiphertextRemainUsable()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();JSONObject d=configured();int overhead=d.toString().getBytes(StandardCharsets.UTF_8).length+24;d.put("syntheticCapacity","x".repeat(VaultCrypto.MAX_PLAINTEXT-overhead-512));open(a,d);set(a,"vaultBytes",d.toString().getBytes(StandardCharsets.UTF_8).length);call(a,"showApp");byte[] original=new VaultStore(a).read();click(a,"补记日期或添加备注");inputs(a.getWindow().getDecorView()).get(1).setText("x".repeat(2000));TestWork.flush(a);assertArrayEquals(original,new VaultStore(a).read());assertTrue(((Diagnostics)TestWork.get(a,"diagnostics")).report().contains("CAPACITY_LIMIT"));click(a,"Synthetic");click(a,"20 分钟");click(a,"保存练习");TestWork.drain(a);assertArrayEquals(original,new VaultStore(a).read());assertEquals(0,data(a).getJSONArray("checkIns").length());assertEquals(2000,inputs(a.getWindow().getDecorView()).get(1).length());
        File exported=new File(a.getCacheDir(),"capacity.rike");set(a,"pendingUri",android.net.Uri.fromFile(exported));set(a,"pendingAction",103);call(a,"consumePending");TestWork.drain(a);assertArrayEquals(original,Files.readAllBytes(exported.toPath()));assertEquals(0,disk(a).getJSONArray("checkIns").length());c.pause().stop().destroy();
    }
    @Test public void editDateFailureKeepsInputAndCancelKeepsRecordThenRetryMovesHeatmapDay()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();JSONObject d=configured();LocalDate first=LocalDate.now(),target=first.minusDays(1);
        Records.upsert(d,"checkIn",new JSONObject().put("id","c").put("practiceTypeName","old").put("practiceDate",first.toString()).put("durationMinutes",20).put("note","old").put("createdAt","2020-01-02T03:04:05Z"));open(a,d);SlowStore store=new SlowStore(a);set(a,"store",store);byte[] before=store.read();int originalLogs=d.getJSONArray("auditLog").length();TestWork.navigate(a,1);
        Method editMethod=MainActivity.class.getDeclaredMethod("editCheckIn",JSONObject.class);editMethod.setAccessible(true);editMethod.invoke(a,data(a).getJSONArray("checkIns").getJSONObject(0));AlertDialog edit=ShadowAlertDialog.getLatestAlertDialog();inputs(edit.getWindow().getDecorView()).get(1).setText("cancelled");edit.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();assertArrayEquals(before,store.read());
        editMethod.invoke(a,data(a).getJSONArray("checkIns").getJSONObject(0));edit=ShadowAlertDialog.getLatestAlertDialog();TestWork.named(edit.getWindow().getDecorView(),"练习日期 · "+first).performClick();AlertDialog calendar=ShadowAlertDialog.getLatestAlertDialog();TestWork.chooseCalendarMonth(calendar,YearMonth.from(target));HeatmapView grid=TestWork.views(calendar.getWindow().getDecorView(),HeatmapView.class).get(0);grid.getAccessibilityNodeProvider().performAction(target.getDayOfMonth()-1,android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,null);
        inputs(edit.getWindow().getDecorView()).get(0).setText("45");store.fail=true;edit.getButton(AlertDialog.BUTTON_POSITIVE).performClick();TestWork.drain(a);assertTrue(edit.isShowing());assertEquals("45",inputs(edit.getWindow().getDecorView()).get(0).getText().toString());assertArrayEquals(before,store.read());store.fail=false;edit.getButton(AlertDialog.BUTTON_POSITIVE).performClick();TestWork.drain(a);
        RecordIndex index=new RecordIndex(data(a),first);assertFalse("Original practice day must no longer contribute minutes",index.stats.minutesByDate.containsKey(first.toString()));assertEquals(Long.valueOf(45),index.stats.minutesByDate.get(target.toString()));assertEquals("2020-01-02T03:04:05Z",data(a).getJSONArray("checkIns").getJSONObject(0).getString("createdAt"));assertEquals("Only successful edit appends history",originalLogs+1,data(a).getJSONArray("auditLog").length());c.pause().stop().destroy();
    }
    @Test public void releaseRecoveryRequiresHiddenFullConfirmationAndPauseBeforeConfirmWritesNothing()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();VaultCrypto.Created created=VaultCrypto.create(PASSWORD.toCharArray());Method show=MainActivity.class.getDeclaredMethod("showRecovery",VaultCrypto.Created.class);show.setAccessible(true);show.invoke(a,created);assertFalse(new VaultStore(a).exists());click(a,"我已离线保存，继续核对");
        assertNull(TestWork.named(a.getWindow().getDecorView(),created.recoveryCode.replaceAll("(.{8})(?!$)","$1 ")));EditText verify=inputs(a.getWindow().getDecorView()).get(0);verify.setText("bad");click(a,"我已离线保存，建立资料库");assertFalse(new VaultStore(a).exists());assertNotNull(verify.getError());c.pause();TestWork.drain(a);assertTrue(created.session.isClosed());assertFalse(new VaultStore(a).exists());c.stop().destroy();
    }

    @Test public void freshRestorePreviewCannotReplaceAFileCreatedByEarlierQueuedWork()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();VaultCrypto.Created key=VaultCrypto.create(PASSWORD.toCharArray());JSONObject current=configured().put("syntheticPreserve",true);byte[] before=VaultCrypto.encrypt(key.session,current.toString().getBytes(StandardCharsets.UTF_8));new VaultStore(a).write(before);key.session.close();
        VaultCrypto.Created other=VaultCrypto.create("other-synthetic".toCharArray());JSONObject replacement=Records.empty();VaultCrypto.Opened opened=VaultCrypto.open(VaultCrypto.encrypt(other.session,replacement.toString().getBytes(StandardCharsets.UTF_8)),"other-synthetic".toCharArray());other.session.close();Arrays.fill(opened.plaintext,(byte)0);
        Method offer=MainActivity.class.getDeclaredMethod("offerFreshRestore",VaultCrypto.Opened.class,JSONObject.class,RecordIndex.class,long.class,boolean.class);offer.setAccessible(true);offer.invoke(a,opened,replacement,new RecordIndex(replacement,LocalDate.now()),TestWork.get(a,"generation"),false);
        assertTrue(opened.session.isClosed());assertNull(TestWork.get(a,"freshRestoreSession"));assertNull(TestWork.get(a,"session"));assertArrayEquals(before,new VaultStore(a).read());assertTrue(disk(a).getBoolean("syntheticPreserve"));c.pause().stop().destroy();
    }

}
