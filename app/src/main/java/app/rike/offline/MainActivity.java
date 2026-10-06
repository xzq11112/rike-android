package app.rike.offline;

import android.app.*;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

/** Native offline application. Plaintext lives only in unlocked process memory.
 * File selection locks the UI; the returned URI is consumed only after unlocking.
 * A generation token prevents background crypto results from unlocking a paused UI.
 */
public final class MainActivity extends Activity implements VaultAccess.Owner {
    private VaultStore store;
    private BiometricVault biometric;
    private android.os.CancellationSignal biometricCancel;
    private VaultCrypto.Session session;
    private JSONObject data;
    private final Diagnostics diagnostics = new Diagnostics(android.os.SystemClock::elapsedRealtime);
    private String diagnosticExport;
    private LinearLayout root, page;
    private ImageButton themeButton;
    private final Map<View,Runnable> themeUpdates=new IdentityHashMap<>();
    private final Map<View,Boolean> waitingControls=new IdentityHashMap<>();
    private Button activeAction,waitingAction;
    private CharSequence waitingLabel;
    private String feedback;
    private boolean suppressAutomaticBiometric;
    private int settingsOrigin;
    private int backupOrigin=5;
    private ScrollView contentScroll;
    private final int[] scrollOffsets=new int[10];
    private int renderedRoute=-1;
    private Object modernBack;
    private ScheduledExecutorService crypto;
    private VaultAccess access;
    private java.time.Clock clock=java.time.Clock.systemDefaultZone();
    private LocalDate todayDate(){return LocalDate.now(clock);}
    private volatile int vaultBytes;
    private Button retryDraft;
    private boolean draftFailed;
    private VaultWriter writer;
    private VaultCrypto.Session writerSession;
    private TextView saveStatus;
    private volatile RecordIndex recordIndex;
    private int historyPage,journalPage,deletedPage;
    private static final int PAGE_SIZE=25;
    private final List<AlertDialog> dialogs = new ArrayList<>();
    private volatile long generation;
    private boolean busy, locking, dark,foreground,biometricAttempted,passwordChecking;
    private final android.os.Handler unlockHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable passwordDebounce;
    private volatile long passwordRevision;
    private long passwordJob;
    private EditText unlockInput;
    private CheckBox unlockRecovery;
    private TextView unlockStatus;
    private Button biometricRetry;
    private int tab;
    private java.time.YearMonth heatmapMonth=java.time.YearMonth.now();
    private int heatmapYear=LocalDate.now().getYear();
    private boolean journalEditing;
    private String managedEntity,maintenance;
    private final List<String> typeOrder=new ArrayList<>();
    private boolean orderDirty,historyFromStats;
    private String historyDate,journalFilter;
    private Runnable refreshPracticeOptions;
    private static final int IMPORT = 101, RESTORE = 102, EXPORT = 103, VERIFY = 104, DIAGNOSTICS = 105;
    private int pendingAction, pickerAction;
    private Uri pendingUri;
    private Uri lastExportUri;
    private String backupOutcome;
    private VaultCrypto.Session freshRestoreSession;
    private String journalDate = LocalDate.now().toString();
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private int ink() { return Color.parseColor(dark ? "#EEE8DE" : "#2B2722"); }
    private int paper() { return Color.parseColor(dark ? "#151411" : "#F5F0E6"); }
    private int surface() { return Color.parseColor(dark ? "#211F1B" : "#FAF7EF"); }
    private int popover() { return Color.parseColor(dark ? "#27231E" : "#FFFDF8"); }
    private int border() { return Color.parseColor(dark ? "#464037" : "#D8D0C2"); }
    private int accent() { return Color.parseColor(dark ? "#D16B5E" : "#A13D32"); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(null); // Never restore framework plaintext form state.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        store = new VaultStore(this);access=VaultAccess.forPath(new File(getNoBackupFilesDir(),"vault.rike").getAbsolutePath());crypto=access.worker;access.acquire(this); biometric = new BiometricVault(this); dark = getPreferences(MODE_PRIVATE).getBoolean("dark", false);
        if(android.os.Build.VERSION.SDK_INT>=33)modernBack=BackApi33.install(this);
        showLocked();
    }
    @Override protected void onResume() { super.onResume();access.acquire(this);foreground=true;suppressAutomaticBiometric=false;startAutomaticBiometric(); }
    @Override protected void onPause() { foreground=false;lock(); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) { /* no plaintext saved state */ }
    @Override protected void onDestroy() { lock();if(android.os.Build.VERSION.SDK_INT>=33&&modernBack!=null)BackApi33.remove(this,modernBack);diagnostics.clear();diagnosticExport=null;access.release(this);super.onDestroy(); }
    @Override public void onBackPressed() {
        if(session==null){moveTaskToBack(true);return;}
        if(busy&&(managedEntity!=null||tab>=4||historyFromStats)){status("正在保存，请稍候；也可以使用右上角锁定。");return;}
        if(managedEntity!=null){leaveManagement(this::showApp);return;}
        
        if(tab==4&&maintenance!=null){maintenance=null;showApp();return;}
        if(tab==4){tab=backupOrigin;showApp();return;}
        if(tab==5){tab=settingsOrigin;showApp();return;}
        if(tab==1&&historyFromStats){historyFromStats=false;tab=3;showApp();return;}
        suppressAutomaticBiometric=true;lock();moveTaskToBack(true);
    }
    private void manualLock(){suppressAutomaticBiometric=true;lock();}
    @android.annotation.TargetApi(33)
    private static final class BackApi33{
        static Object install(MainActivity a){android.window.OnBackInvokedCallback c=a::onBackPressed;a.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,c);return c;}
        static void remove(MainActivity a,Object c){a.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback)c);}
    }
    public void revokeVault(){foreground=false;manualLock();}
    private boolean unlocked() { return access!=null&&access.owns(this)&&!locking && session != null && !session.isClosed() && data != null; }
    private void wipe(View v) {
        if (v instanceof TextView) ((TextView)v).setText("");
        if (v instanceof ViewGroup) for (int i=0; i<((ViewGroup)v).getChildCount(); i++) wipe(((ViewGroup)v).getChildAt(i));
    }
    private void lock() {
        locking = true; generation++; setBusy(false);cancelAutomaticUnlock();
        if (biometricCancel != null) { biometricCancel.cancel(); biometricCancel = null; }
        if(biometric!=null&&crypto!=null)crypto.execute(()->{try{biometric.cancelEnrollment();}catch(Exception ignored){}});
        for (AlertDialog d : new ArrayList<>(dialogs)) d.dismiss();
        if(writer!=null){writer.close();writer=null;writerSession=null;}
        if (session != null) session.close();
        if(freshRestoreSession!=null){freshRestoreSession.close();freshRestoreSession=null;}
        session = null; data = null; recordIndex=null; historyDate=null;journalFilter=null;journalEditing=false;refreshPracticeOptions = null; themeUpdates.clear();themeButton=null;
        managedEntity=null;typeOrder.clear();orderDirty=false;maintenance=null;historyFromStats=false;
        feedback=null;waitingControls.clear();activeAction=null;waitingAction=null;waitingLabel=null;
        saveStatus=null;retryDraft=null;draftFailed=false;vaultBytes=0;
        lastExportUri=null;backupOutcome=null;
        Arrays.fill(scrollOffsets,0);renderedRoute=-1;contentScroll=null;
        if (root != null) wipe(root);
        locking = false;
        if (store != null && !isFinishing()) showLocked();
    }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row(LinearLayout parent) {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); parent.addView(l); return l;
    }
    private void base() {
        themeUpdates.clear();themeButton=null;
        root = column(); root.setBackground(new PaperBackground(getResources().getDisplayMetrics().density,dark)); root.setPadding(dp(16),dp(12),dp(16),dp(8));
        root.setSaveEnabled(false); root.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        getWindow().setStatusBarColor(paper()); getWindow().setNavigationBarColor(paper());
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        setContentView(root);
    }
    private void scroll() {
        ScrollView s = new ScrollView(this); page = column(); s.addView(page);
        s.setSaveEnabled(false);contentScroll=s;
        root.addView(s,new LinearLayout.LayoutParams(-1,0,1));
    }
    private TextView text(LinearLayout p, String t, int size) {
        TextView v=new TextView(this); v.setText(t); v.setTextSize(size); v.setTextColor(ink());
        v.setPadding(0,dp(size>=22?12:6),0,dp(size>=22?8:6));
        v.setLineSpacing(dp(4),1.15f);
        if(size>=18){v.setTypeface(android.graphics.Typeface.create("serif",android.graphics.Typeface.NORMAL));v.setLetterSpacing(0.02f);} v.setSaveEnabled(false); p.addView(v);
        themeUpdates.put(v,()->v.setTextColor(ink()));return v;
    }
    private GradientDrawable bg(int color) {
        GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(12));
        d.setStroke(dp(1),border()); return d;
    }
    private Button button(LinearLayout p, String label, Runnable action) {
        Button b=new Button(this); b.setText(label); b.setTextSize(15); b.setPadding(dp(12),dp(12),dp(12),dp(12)); b.setAllCaps(false);
        b.setTextColor(ink()); b.setBackground(bg(popover())); b.setMinHeight(dp(48)); b.setFilterTouchesWhenObscured(true);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.setMargins(0,dp(4),dp(4),dp(4));
        if(p.getOrientation()==LinearLayout.HORIZONTAL){lp.width=0;lp.weight=1;} p.addView(b,lp);
        themeUpdates.put(b,()->{
            boolean yes=b.isSelected();GradientDrawable background=(GradientDrawable)b.getBackground();
            background.setColor(yes?accent():popover());background.setStroke(dp(1),yes?accent():border());
            b.setTextColor(yes?(dark?0xff211e1a:Color.WHITE):ink());
        });
        b.setOnClickListener(v->{if(!busy||label.equals("锁定")){activeAction=b;try{action.run();}finally{activeAction=null;}}});return b;
    }
    private void selected(Button b,boolean yes){b.setSelected(yes);themeUpdates.get(b).run();}
    private void forgetTheme(View view){
        themeUpdates.remove(view);
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)forgetTheme(((ViewGroup)view).getChildAt(i));
    }
    private void applyTheme(){
        if(root.getBackground() instanceof PaperBackground)((PaperBackground)root.getBackground()).theme(dark);else root.setBackgroundColor(paper());getWindow().setStatusBarColor(paper());getWindow().setNavigationBarColor(paper());
        getWindow().getDecorView().setSystemUiVisibility(dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        for(Runnable update:themeUpdates.values())update.run();
        if(themeButton!=null)themeButton.setContentDescription(dark?"切换日间":"切换夜间");
    }
    private EditText field(LinearLayout p,String label,String value,boolean multiline,boolean secret) {
        text(p,label,14); EditText e=new EditText(this); e.setTextColor(ink()); e.setTextSize(16);
        e.setPadding(dp(12),dp(10),dp(12),dp(10)); e.setBackground(bg(popover()));
        themeUpdates.put(e,()->{e.setTextColor(ink());GradientDrawable background=(GradientDrawable)e.getBackground();background.setColor(popover());background.setStroke(dp(1),border());});
        e.setSaveEnabled(false); e.setFreezesText(false); e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        e.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING|EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        e.setInputType(secret?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:
            InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS|(multiline?InputType.TYPE_TEXT_FLAG_MULTI_LINE:0));
        if(multiline){e.setMinLines(4);e.setGravity(Gravity.TOP);}
        e.setText(value); e.setCustomSelectionActionModeCallback(new ActionMode.Callback(){
            private void localOnly(Menu menu){menu.clear();menu.add(0,android.R.id.selectAll,0,"全选");}
            public boolean onCreateActionMode(ActionMode m,Menu menu){if(secret)return false;localOnly(menu);return true;}
            public boolean onPrepareActionMode(ActionMode m,Menu menu){if(secret)return false;localOnly(menu);return true;}
            public boolean onActionItemClicked(ActionMode m,MenuItem item){if(!secret&&item.getItemId()==android.R.id.selectAll){e.selectAll();return true;}return false;}
            public void onDestroyActionMode(ActionMode m){}
        });
        e.setCustomInsertionActionModeCallback(new ActionMode.Callback(){
            public boolean onCreateActionMode(ActionMode m,Menu menu){return false;}
            public boolean onPrepareActionMode(ActionMode m,Menu menu){return false;}
            public boolean onActionItemClicked(ActionMode m,MenuItem item){return false;}
            public void onDestroyActionMode(ActionMode m){}
        });
        p.addView(e,new LinearLayout.LayoutParams(-1,-2)); return e;
    }
    private Button passwordKeyboard(LinearLayout parent,EditText... fields) {
        for(EditText e:fields)if(e!=null)e.setRawInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        boolean[] numeric={!getPreferences(MODE_PRIVATE).getBoolean("characterKeyboard",false)};
        for(EditText e:fields)if(e!=null)e.setRawInputType(numeric[0]?InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        Button[] control=new Button[1];
        control[0]=button(parent,numeric[0]?"使用字符键盘":"使用数字键盘",()->{
            numeric[0]=!numeric[0];
            getPreferences(MODE_PRIVATE).edit().putBoolean("characterKeyboard",!numeric[0]).apply();
            for(EditText e:fields)if(e!=null){
                e.setRawInputType(numeric[0]?InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
                e.setSelection(e.length());
                if(e.hasFocus())((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).restartInput(e);
            }
            // Label follows the active input mode; this action never changes the secret.
            control[0].setText(numeric[0]?"使用字符键盘":"使用数字键盘");
        });
        return control[0];
    }
    private int passwordInputType(boolean recovery){return recovery||getPreferences(MODE_PRIVATE).getBoolean("characterKeyboard",false)?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD;}
    private void watch(EditText e,Runnable action){ e.addTextChangedListener(new TextWatcher(){
        public void beforeTextChanged(CharSequence s,int start,int count,int after){}
        public void onTextChanged(CharSequence s,int start,int before,int count){}
        public void afterTextChanged(Editable s){if(unlocked()&&!busy)action.run();}
    });}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void fail(Exception e){
        Diagnostics.Code code = e instanceof VaultCapacityException ? Diagnostics.Code.CAPACITY_LIMIT
            : e instanceof java.security.GeneralSecurityException ? Diagnostics.Code.AUTH_OR_FILE_INVALID
            : e instanceof IOException ? Diagnostics.Code.STORAGE_IO
            : e instanceof IllegalArgumentException || e instanceof JSONException ? Diagnostics.Code.DATA_INVALID
            : Diagnostics.Code.OPERATION_FAILED;
        diagnostics.record(code);
        // Neither diagnostic report nor toast reflects untrusted exception messages.
        String message=code==Diagnostics.Code.CAPACITY_LIMIT?"本次保存会超过资料库 16 MiB 上限，尚未写入；原资料与当前输入保留。请先导出并验证完整备份。操作历史、已删除内容和旧归档也占容量，移到已删除不会释放空间。"
            :code==Diagnostics.Code.STORAGE_IO?"未能完成文件操作，请检查本机存储空间和文件访问权限。原资料保留。"
            :code==Diagnostics.Code.AUTH_OR_FILE_INVALID?"密码不匹配，或文件损坏/不受支持。请核对备份导出时的密码；原资料保留。"
            :code==Diagnostics.Code.DATA_INVALID?"输入或文件格式不符合要求，请核对后重试。原资料保留。"
            :"操作未完成，请重试。原资料保留；可在本地诊断中查看错误码。";
        feedback=message;status(message);toast(message);
    }
    private void dialog(AlertDialog d) {
        dialogs.add(d); d.setOnDismissListener(x->{if(d.getWindow()!=null){wipe(d.getWindow().getDecorView());forgetTheme(d.getWindow().getDecorView());}dialogs.remove(d);});
        d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);d.getWindow().setBackgroundDrawable(bg(paper())); d.show();
    }
    private android.content.Context dialogContext(){return new ContextThemeWrapper(this,dark?android.R.style.Theme_Material_Dialog_Alert:android.R.style.Theme_Material_Light_Dialog_Alert);}
    private void confirm(String title,String message,Runnable yes){
        AlertDialog d=new AlertDialog.Builder(dialogContext()).setTitle(title).setMessage(message)
            .setNegativeButton("取消",null).setPositiveButton("确认",(x,w)->yes.run()).create();dialog(d);
        d.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(accent());
    }
    private void inputsEnabled(boolean enabled){if(root!=null)forInputs(root,enabled);}
    /** Visible waiting state. Kept entirely in memory; locking remains available. */
    private void setBusy(boolean waiting){
        busy=waiting;
        if(waiting){
            if(waitingAction==null&&activeAction!=null){waitingAction=activeAction;waitingLabel=activeAction.getText();String label=waitingLabel.toString();activeAction.setText(label.contains("保存")||label.contains("完成")?"保存中…":label.contains("验证")?"验证中…":label.contains("读取")?"读取中…":label.contains("恢复")?"恢复中…":"处理中…");}
            gateControls(root);
            for(AlertDialog d:dialogs)if(d.isShowing()&&d.getWindow()!=null)gateControls(d.getWindow().getDecorView());
        }else{
            for(Map.Entry<View,Boolean> e:waitingControls.entrySet()){e.getKey().setEnabled(e.getValue());e.getKey().setAlpha(e.getValue()?1f:0.45f);}
            waitingControls.clear();
            if(waitingAction!=null&&waitingLabel!=null)waitingAction.setText(waitingLabel);
            waitingAction=null;waitingLabel=null;
        }
    }
    private void gateControls(View v){
        if(v==null)return;
        if((v instanceof EditText||v.isClickable())&&!"锁定".contentEquals(v.getContentDescription()==null?"":v.getContentDescription())){
            waitingControls.putIfAbsent(v,v.isEnabled());v.setEnabled(false);v.setAlpha(0.45f);
        }
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)gateControls(((ViewGroup)v).getChildAt(i));
    }
    private void saved(String message){feedback=message;status(message);}
    private void forInputs(View view,boolean enabled){
        if(view instanceof EditText)view.setEnabled(enabled);
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)forInputs(((ViewGroup)view).getChildAt(i),enabled);
    }
    private void status(String label){if(saveStatus!=null){saveStatus.setVisibility(label.isEmpty()?View.GONE:View.VISIBLE);if(!label.contentEquals(saveStatus.getText()))saveStatus.setText(label);}}
    private VaultWriter writer(){
        if(writer==null||writerSession!=session){
            if(writer!=null)writer.close();
            long token=generation;VaultCrypto.Session owner=session;
            writerSession=owner;
            VaultWriter[] holder=new VaultWriter[1];
            writer=new VaultWriter(crypto,owner,data,this::persist,(next,error)->{
                if(token!=generation)return;
                RecordIndex prepared=next==null?null:buildIndex(next);
                runOnUiThread(()->{
                    if(token!=generation||writer!=holder[0]||!unlocked())return;
                    if(error!=null){draftFailed=true;fail(error);status("草稿尚未写入；原已保存资料仍在。请重试草稿保存。"+(error instanceof VaultCapacityException?" 本次草稿超过 16 MiB 上限，请先导出完整备份。":""));if(retryDraft!=null)retryDraft.setVisibility(View.VISIBLE);}
                    else{draftFailed=false;if(retryDraft!=null)retryDraft.setVisibility(View.GONE);data=next;recordIndex=prepared;status(writer.dirty()?"草稿待保存…（尚未提交记录）":"草稿已本机加密保存（尚未提交记录）");}
                });
            });holder[0]=writer;
        }
        return writer;
    }
    private void changeAsync(VaultWriter.Edit edit,Runnable success){changeAsync(edit,success,()->{});}
    private void changeAsync(VaultWriter.Edit edit,Runnable success,Runnable failure){
        if(!unlocked()||busy)return;
        long token=generation;VaultCrypto.Session owner=session;
        setBusy(true);inputsEnabled(false);status("正在本机加密保存…");
        writer().edit(edit,(next,error)->completeWrite(token,owner,next,error,success,failure));
    }
    private void completeWrite(long token,VaultCrypto.Session owner,JSONObject next,Exception error,Runnable success,Runnable failure){
        RecordIndex prepared=next==null?null:buildIndex(next);
        runOnUiThread(()->{
            if(token!=generation||session!=owner||!unlocked())return;
            setBusy(false);inputsEnabled(true);
            if(error!=null){draftFailed=writer!=null&&writer.dirty();if(retryDraft!=null)retryDraft.setVisibility(draftFailed?View.VISIBLE:View.GONE);fail(error);status("正式保存未完成，原已保存资料保留；当前输入请重试保存。"+(draftFailed?" 草稿也尚未写入，可重试草稿保存。":""));failure.run();return;}
            draftFailed=false;if(retryDraft!=null)retryDraft.setVisibility(View.GONE);data=next;recordIndex=prepared;status("已本机加密保存");success.run();
        });
    }
    private void replaceAsync(JSONObject replacement,Runnable success){
        replaceAsync(replacement,success,()->{});
    }
    private void replaceAsync(JSONObject replacement,Runnable success,Runnable failure){
        if(!unlocked()||busy)return;
        long token=generation;VaultCrypto.Session owner=session;setBusy(true);inputsEnabled(false);status("正在本机加密保存…");
        recordIndex=null;
        writer().replace(replacement,(next,error)->{recordIndex=null;completeWrite(token,owner,next,error,success,failure);});
    }
    private void persist(VaultCrypto.Session key,JSONObject next)throws Exception{
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new IllegalStateException("Vault writes require worker thread");
        long start=android.os.SystemClock.elapsedRealtime();
        byte[] plain=next.toString().getBytes(StandardCharsets.UTF_8);
        try{store.write(VaultCrypto.encrypt(key,plain));vaultBytes=plain.length;}
        catch(Exception e){diagnostics.record(Diagnostics.Code.VAULT_WRITE_FAILED);throw e;}
        finally{Arrays.fill(plain,(byte)0);if(android.os.SystemClock.elapsedRealtime()-start>=250)diagnostics.record(Diagnostics.Code.SAVE_SLOW);}
    }
    private RecordIndex buildIndex(JSONObject next){
        try{RecordIndex current=recordIndex;LocalDate day=todayDate();return current!=null&&current.matches(next,day)?current:new RecordIndex(next,day);}
        catch(Exception e){diagnostics.record(Diagnostics.Code.DATA_INVALID);return null;}
    }
    private RecordIndex index()throws Exception{
        RecordIndex current=recordIndex;
        // Production unlock/import/save prepares this on worker. Fallback supports
        // small injected fixtures and a calendar date rollover.
        if(current==null||!current.matches(data,todayDate())){current=new RecordIndex(data,todayDate());recordIndex=current;}
        return current;
    }
    private void pageControls(int total,int selected,java.util.function.IntConsumer change){
        int pages=Math.max(1,(total+PAGE_SIZE-1)/PAGE_SIZE);LinearLayout controls=row(page);
        button(controls,"上一页",()->{if(selected>0)change.accept(selected-1);});
        text(controls,(selected+1)+" / "+pages+" 页",13);
        button(controls,"下一页",()->{if(selected+1<pages)change.accept(selected+1);});
    }
    private JSONObject draft(String name)throws Exception{return writer().draft(name);}
    private void saveDraft(String name,JSONObject value){
        try{writer().stage(name,value);feedback=null;status("草稿待保存…（尚未提交记录）");}catch(Exception e){fail(e);}
    }
    private JSONObject object(String... pairs)throws Exception{
        JSONObject r=new JSONObject();for(int i=0;i<pairs.length;i+=2)r.put(pairs[i],pairs[i+1]);return r;
    }
    private void showLocked() {
        cancelAutomaticUnlock();biometricAttempted=false;
        base();scroll();text(page,"日 课",34);text(page,"静观 · 日有所记",16);
        boolean fresh=!store.exists(), restoring=fresh&&pendingAction==RESTORE&&pendingUri!=null, creating=fresh&&!restoring;
        text(page,creating&&BuildConfig.DEBUG?"本机加密 · 不联网\n体验测试版，请先使用虚构记录。":"本机加密 · 不联网",13);
        if(pendingUri!=null||pickerAction!=0)text(page,handoffLabel(pendingUri!=null?pendingAction:pickerAction),14);
        EditText pass=field(page,creating?"设置主密码":restoring?"备份导出时的主密码":"输入主密码","",false,true);
        EditText again=creating?field(page,"再次输入主密码","",false,true):null;
        Button keyboard=passwordKeyboard(page,pass,again);
        if(creating){
            text(page,"请牢记主密码",19);
            text(page,"记录和备份都是密文。密码不会上传，没有服务器替你找回；忘记密码时，仅有备份文件仍无法解密。",14);
            text(page,BuildConfig.DEBUG?"本测试版不提供恢复密钥；忘记主密码后，加密备份无法恢复。":"正式版下一步会提供离线恢复密钥，它也能解密。只有提前妥善保存该密钥，才可在忘记密码时恢复；密码与密钥都丢失则无法恢复。",14);
            text(page,"指纹只帮助解锁这台手机，换机仍需要备份密码或对应恢复密钥。",14);
            LinearLayout details=column();page.addView(details);details.setVisibility(View.GONE);
            button(page,"了解密码与备份",()->details.setVisibility(details.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));
            text(details,"主密码不限制位数，但短密码容易被猜解，建议使用较长的随机口令。更换密码后，旧备份仍需要导出时的旧密码（或对应的恢复密钥）。",14);
        }
        CheckBox recovery=new CheckBox(this);recovery.setText("使用离线恢复密钥");recovery.setTextColor(ink());
        // Preview builds intentionally avoid recovery-key UI so phone testing stays quick.
        // Release builds keep the recovery path for real private data.
        if(!creating&&!BuildConfig.DEBUG){page.addView(recovery);recovery.setOnCheckedChangeListener((b,checked)->{if(checked){pass.setRawInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);keyboard.setVisibility(View.GONE);}else{pass.setRawInputType(passwordInputType(false));keyboard.setVisibility(View.VISIBLE);}});}
        if(creating||restoring)selected(button(page,creating?"建立资料库":"恢复资料库",()->{
            String value=pass.getText().toString();boolean recover=!BuildConfig.DEBUG&&recovery.isChecked();
            if(creating&&!value.equals(again.getText().toString())){toast("两次密码不一致");return;}
            if(creating){try{VaultCrypto.requirePassword(value.toCharArray());}catch(Exception e){fail(e);return;}}
            pass.setText("");if(again!=null)again.setText("");setBusy(true);long ticket=generation;Uri uri=restoring?pendingUri:null;
            text(page,"正在处理，请保持应用在前台……",14);
            crypto.execute(()->{
                VaultCrypto.Created made=null;VaultCrypto.Opened opened=null;JSONObject parsed=null;RecordIndex prepared=null;Exception error=null;char[] chars=value.toCharArray();
                try{
                    if(creating){if(store.exists())throw new IllegalStateException("Vault already created");made=VaultCrypto.create(chars);if(BuildConfig.DEBUG){parsed=Records.empty();persist(made.session,parsed);}}
                    else {
                        byte[] file=uri==null?store.read():readUri(uri);
                        opened=recover?VaultCrypto.recover(file,value):VaultCrypto.open(file,chars);
                        try{parsed=Records.validate(new JSONObject(new String(opened.plaintext,StandardCharsets.UTF_8)));vaultBytes=opened.plaintext.length;}
                        finally{Arrays.fill(opened.plaintext,(byte)0);}
                    }
                    if(parsed!=null)prepared=buildIndex(parsed);
                }catch(Exception e){error=e;}finally{Arrays.fill(chars,'\0');}
                RecordIndex preparedIndex=prepared;
                VaultCrypto.Created c=made;VaultCrypto.Opened o=opened;JSONObject result=parsed;Exception problem=error;
                runOnUiThread(()->{
                    if(ticket!=generation||isFinishing()){if(c!=null)c.session.close();if(o!=null)o.session.close();return;}
                    setBusy(false);
                    if(problem!=null){if(c!=null)c.session.close();if(o!=null)o.session.close();fail(problem);showLocked();return;}
                    if(c!=null){
                        if(BuildConfig.DEBUG){
                            try{
                                session=c.session;data=result;recordIndex=preparedIndex;
                                toast("测试资料库已建立");showApp();
                            }catch(Exception e){c.session.close();session=null;data=null;fail(e);showLocked();}
                        }else showRecovery(c);
                        return;
                    }
                    try{
                        if(restoring){offerFreshRestore(o,result,preparedIndex,ticket,recover);return;}
                        session=o.session;data=result;recordIndex=preparedIndex;
                        if(restoring){pendingUri=null;pendingAction=0;}
                        showApp();if(recover)changePassword();else consumePending();
                    }catch(Exception e){o.session.close();fail(e);lock();}
                });
            });
        }),true);
        if(creating){
            text(page,BuildConfig.DEBUG?"请牢记主密码，并定期导出和验证加密备份。":"请保管好主密码与离线恢复密钥，并定期验证加密备份。",14);
            button(page,"从加密备份恢复",()->pick(RESTORE));
        }
        if(restoring)button(page,"取消恢复",()->{pendingUri=null;pendingAction=0;showLocked();});
        if(!fresh){
            unlockInput=pass;unlockRecovery=recovery;unlockStatus=text(page,"输入正确密码后会自动解锁，无需再点确认。",14);
            pass.addTextChangedListener(new TextWatcher(){
                public void beforeTextChanged(CharSequence t,int start,int count,int after){}
                public void onTextChanged(CharSequence t,int start,int before,int count){}
                public void afterTextChanged(Editable t){queuePasswordProbe();}
            });
            recovery.setOnCheckedChangeListener((b,checked)->{
                pass.setRawInputType(passwordInputType(checked));keyboard.setVisibility(checked?View.GONE:View.VISIBLE);queuePasswordProbe();
            });
            if(biometric.enabled()&&android.os.Build.VERSION.SDK_INT>=28){
                text(page,suppressAutomaticBiometric?"已手动锁定。输入主密码，或主动开始指纹验证。":"已启用指纹：进入时自动验证，也可输入主密码。",14);
                if(suppressAutomaticBiometric)button(page,"解锁",()->{suppressAutomaticBiometric=false;biometricAttempted=false;startAutomaticBiometric();});
                biometricRetry=button(page,"重试指纹",()->{biometricAttempted=false;startAutomaticBiometric();});biometricRetry.setVisibility(View.GONE);
                startAutomaticBiometric();
            }
        }
    }
    private void cancelAutomaticUnlock(){
        passwordRevision++;passwordJob++;passwordChecking=false;
        if(passwordDebounce!=null)unlockHandler.removeCallbacks(passwordDebounce);passwordDebounce=null;
        unlockInput=null;unlockRecovery=null;unlockStatus=null;biometricRetry=null;
    }
    private void queuePasswordProbe(){
        passwordRevision++;
        if(passwordDebounce!=null)unlockHandler.removeCallbacks(passwordDebounce);
        if(unlockInput==null||!foreground||locking||unlocked())return;
        if(unlockInput.length()==0){if(unlockStatus!=null)unlockStatus.setText("输入正确密码后会自动解锁，无需再点确认。");return;}
        passwordDebounce=()->{passwordDebounce=null;probePassword();};unlockHandler.postDelayed(passwordDebounce,550);
    }
    /** Single background probe at a time; changed input and lifecycle invalidate results. */
    private void probePassword(){
        if(unlockInput==null||!foreground||busy||locking||unlocked()||passwordChecking)return;
        EditText input=unlockInput;boolean recover=!BuildConfig.DEBUG&&unlockRecovery.isChecked();
        int size=input.length();if(size==0||size>1024){if(unlockStatus!=null)unlockStatus.setText(size==0?"请输入主密码。":"输入超出支持的长度，请检查。");return;}
        char[] chars=new char[size];input.getText().getChars(0,size,chars,0);
        long token=generation,revision=passwordRevision,job=++passwordJob;passwordChecking=true;
        if(unlockStatus!=null)unlockStatus.setText("正在本机验证…");
        crypto.execute(()->{
            VaultCrypto.Opened opened=null;JSONObject parsed=null;RecordIndex indexed=null;Exception error=null;
            try{
                if(token!=generation||revision!=passwordRevision)throw new CancellationException();
                opened=recover?VaultCrypto.recover(store.read(),new String(chars)):VaultCrypto.open(store.read(),chars);
                parsed=Records.validate(new JSONObject(new String(opened.plaintext,StandardCharsets.UTF_8)));vaultBytes=opened.plaintext.length;indexed=buildIndex(parsed);
            }catch(Exception e){error=e;}
            finally{Arrays.fill(chars,'\0');if(opened!=null)Arrays.fill(opened.plaintext,(byte)0);}
            VaultCrypto.Opened got=opened;JSONObject result=parsed;RecordIndex prepared=indexed;Exception problem=error;
            runOnUiThread(()->{
                if(job!=passwordJob||token!=generation||input!=unlockInput||!foreground||isFinishing()){if(got!=null)got.session.close();return;}
                passwordChecking=false;
                if(revision!=passwordRevision||busy){if(got!=null)got.session.close();queuePasswordProbe();return;}
                if(problem!=null){
                    if(got!=null)got.session.close();
                    if(unlockStatus!=null)unlockStatus.setText("尚未解锁，请继续输入或检查密码。");
                    // A paused partial password is normal; don't emit error toasts for it.
                    if(!(problem instanceof java.security.GeneralSecurityException))fail(problem);
                    return;
                }
                if(got==null)return;
                cancelAutomaticUnlock();input.setText("");session=got.session;data=result;recordIndex=prepared;
                ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);
                showApp();if(recover)changePassword();else consumePending();
            });
        });
    }
    private void startAutomaticBiometric(){
        if(!foreground||suppressAutomaticBiometric||isFinishing()||unlocked()||unlockInput==null||busy||biometricAttempted||android.os.Build.VERSION.SDK_INT<28||!biometric.enabled())return;
        biometricAttempted=true;authenticateBiometric(false);
    }
    private void biometricFallback(){
        crypto.execute(()->{try{biometric.cancelEnrollment();}catch(Exception ignored){}});
        setBusy(false);inputsEnabled(true);biometricCancel=null;
        if(biometricRetry!=null)biometricRetry.setVisibility(View.VISIBLE);
        if(unlockStatus!=null)unlockStatus.setText("可输入主密码自动解锁，或重试指纹。");
        queuePasswordProbe();
    }
    private void showRecovery(VaultCrypto.Created created){
        session=created.session;base();scroll();text(page,"1 / 2 · 离线保存恢复密钥",24);
        text(page,"将它写在纸上，和手机分开保管。它能直接解密资料库，不能发给任何人。切到后台会取消尚未确认的建立。",15);
        text(page,created.recoveryCode.replaceAll("(.{8})(?!$)","$1 "),18);
        button(page,"我已离线保存，继续核对",()->verifyRecovery(created));
    }
    private void verifyRecovery(VaultCrypto.Created created){
        base();scroll();text(page,"2 / 2 · 核对恢复密钥",24);
        text(page,"请根据离线副本完整重输密钥。确认前还没有建立资料库。",15);
        button(page,"返回查看密钥",()->showRecovery(created));
        EditText verify=field(page,"完整重输恢复密钥确认已保存","",true,true);
        button(page,"我已离线保存，建立资料库",()->{
            if(!verify.getText().toString().replaceAll("\\s", "").trim().equalsIgnoreCase(created.recoveryCode)){verify.setError("恢复密钥不一致，请核对离线副本。");return;}
            setBusy(true);long token=generation;VaultCrypto.Session owner=session,key=VaultCrypto.duplicate(session);
            crypto.execute(()->{
                JSONObject empty=null;Exception error=null;
                try{if(store.exists())throw new IllegalStateException("Vault already created");empty=Records.empty();persist(key,empty);}catch(Exception e){error=e;}finally{key.close();}
                JSONObject result=empty;Exception problem=error;RecordIndex prepared=result==null?null:buildIndex(result);
                runOnUiThread(()->{if(token!=generation||session!=owner)return;setBusy(false);if(problem!=null){fail(problem);return;}data=result;recordIndex=prepared;showApp();});
            });
        });
    }
    private void offerFreshRestore(VaultCrypto.Opened opened,JSONObject restored,RecordIndex prepared,long token,boolean recovery)throws Exception{
        if(store.exists()){opened.session.close();showLocked();toast("本机资料库已建立，请先解锁，再继续从备份恢复。原资料保留。");return;}
        freshRestoreSession=opened.session;
        boolean[] committing={false};
        Runnable cancel=()->{if(token!=generation||committing[0])return;if(freshRestoreSession!=null)freshRestoreSession.close();freshRestoreSession=null;pendingUri=null;pendingAction=0;showLocked();};
        AlertDialog d=new AlertDialog.Builder(dialogContext()).setTitle("核对备份，再恢复")
            .setMessage(Records.summary(restored)+"\n恢复后，本机资料库采用这份备份的密码。若使用恢复密钥，会提示设置新主密码。")
            .setNegativeButton("取消恢复",(x,w)->cancel.run()).setPositiveButton("恢复到这台手机",null).create();
        d.setOnCancelListener(x->cancel.run());dialog(d);
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(busy||token!=generation||freshRestoreSession!=opened.session||store.exists())return;
            committing[0]=true;
            d.setCancelable(false);d.setCanceledOnTouchOutside(false);
            activeAction=d.getButton(AlertDialog.BUTTON_POSITIVE);setBusy(true);activeAction=null;
            VaultCrypto.Session worker=VaultCrypto.duplicate(opened.session);
            crypto.execute(()->{
                Exception error=null;try{if(store.exists())throw new IllegalStateException("Vault already created");persist(worker,restored);}catch(Exception e){error=e;}finally{worker.close();}
                Exception failure=error;runOnUiThread(()->{
                    if(token!=generation||freshRestoreSession!=opened.session)return;setBusy(false);
                    if(failure!=null){committing[0]=false;d.setCancelable(true);d.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);diagnostics.record(Diagnostics.Code.RESTORE_WRITE_FAILED);fail(failure);return;}
                    d.dismiss();freshRestoreSession=null;session=opened.session;data=restored;recordIndex=prepared;pendingUri=null;pendingAction=0;
                    saved("备份已恢复到本机。请核对练习设置与记录。");showApp();if(recovery)changePassword();
                });
            });
        });
    }
    private void showApp(){
        if(!unlocked())return;
        long renderStart=android.os.SystemClock.elapsedRealtime();
        if(writer!=null&&writerSession==session&&writer.current()!=null)data=writer.current();
        if(recordIndex!=null&&!recordIndex.day.equals(todayDate())){
            JSONObject snapshot=data;long token=generation;setBusy(true);inputsEnabled(false);
            crypto.execute(()->{RecordIndex prepared=buildIndex(snapshot);runOnUiThread(()->{if(token!=generation||!unlocked())return;recordIndex=prepared;setBusy(false);inputsEnabled(true);showApp();});});return;
        }
        if(contentScroll!=null&&renderedRoute>=0)scrollOffsets[renderedRoute]=contentScroll.getScrollY();
        refreshPracticeOptions=null;
        base();LinearLayout top=row(root);top.setGravity(Gravity.CENTER_VERTICAL);
        TextView seal=text(top,"日",18);seal.setGravity(Gravity.CENTER);seal.setPadding(0,0,0,0);seal.setRotation(-2);GradientDrawable sealBackground=bg(popover());sealBackground.setCornerRadius(dp(5));sealBackground.setStroke(dp(1),accent());seal.setBackground(sealBackground);seal.setTextColor(accent());LinearLayout.LayoutParams sealParams=new LinearLayout.LayoutParams(dp(32),dp(32));sealParams.setMargins(0,0,dp(8),0);seal.setLayoutParams(sealParams);themeUpdates.put(seal,()->{sealBackground.setColor(popover());sealBackground.setStroke(dp(1),accent());seal.setTextColor(accent());});
        TextView title=text(top,"日课",18);title.setPadding(0,dp(4),0,dp(4));title.setLayoutParams(new LinearLayout.LayoutParams(0,dp(48),1));title.setGravity(Gravity.CENTER_VERTICAL);
        themeButton=headerIcon(top,dark?ZenIcon.SUN:ZenIcon.MOON,dark?"切换日间":"切换夜间",()->{dark=!dark;getPreferences(MODE_PRIVATE).edit().putBoolean("dark",dark).apply();applyTheme();});
        headerIcon(top,ZenIcon.LOCK,"锁定",this::manualLock);
        saveStatus=text(root,writer!=null&&writer.dirty()?"草稿待保存…（尚未提交记录）":feedback==null?"":feedback,12);saveStatus.setPadding(0,0,0,dp(4));saveStatus.setMinHeight(dp(16));saveStatus.setVisibility(saveStatus.length()==0?View.GONE:View.VISIBLE);saveStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        retryDraft=button(root,"重试草稿保存",()->{if(writer!=null){status("正在重试草稿保存…（尚未提交记录）");retryDraft.setVisibility(View.GONE);writer.flushSoon();}});retryDraft.setVisibility(draftFailed?View.VISIBLE:View.GONE);
        if(vaultBytes>=VaultCrypto.MAX_PLAINTEXT*0.8)text(root,capacityLabel(),12);
        String[] labels={"今日","记录","日记","数据","设置"};LinearLayout nav=row(root);
        for(int i=0;i<labels.length;i++){final int index=i==4?5:i;Button b=button(nav,labels[i],()->leaveManagement(()->{if(index==5&&tab<4)settingsOrigin=tab;historyFromStats=false;journalEditing=false;maintenance=null;tab=index;showApp();}));b.setTextSize(14);b.setPadding(dp(4),dp(4),dp(4),dp(4));b.setMinimumHeight(dp(48));b.setLayoutParams(new LinearLayout.LayoutParams(0,dp(48),1));GradientDrawable fill=new GradientDrawable();fill.setColor(Color.TRANSPARENT);GradientDrawable underline=new GradientDrawable();android.graphics.drawable.LayerDrawable navigation=new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{fill,underline});navigation.setLayerHeight(1,dp(2));navigation.setLayerGravity(1,Gravity.BOTTOM);b.setBackground(navigation);themeUpdates.put(b,()->{b.setTextColor(b.isSelected()?accent():ink());underline.setColor(b.isSelected()?accent():Color.TRANSPARENT);});selected(b,index==tab);}
        scroll();
        renderedRoute=maintenance!=null?8:tab;
        try{switch(tab){case 0:today();break;case 1:history();break;case 2:journals();break;case 3:statistics();break;case 4:backups();break;case 5:settings();break;}}
        catch(Exception e){fail(e);}
        ScrollView rendered=contentScroll;int offset=scrollOffsets[renderedRoute];long token=generation;
        rendered.post(()->{if(token==generation&&contentScroll==rendered&&unlocked())rendered.scrollTo(0,offset);});
        if(android.os.SystemClock.elapsedRealtime()-renderStart>=250)diagnostics.record(Diagnostics.Code.PAGE_BUILD_SLOW);
    }
    private ImageButton headerIcon(LinearLayout parent,int kind,String label,Runnable action){
        ImageButton b=new ImageButton(this);ZenIcon icon=new ZenIcon(kind,ink());b.setImageDrawable(icon);b.setPadding(dp(13),dp(13),dp(13),dp(13));b.setContentDescription(label);b.setBackgroundColor(Color.TRANSPARENT);b.setFilterTouchesWhenObscured(true);parent.addView(b,new LinearLayout.LayoutParams(dp(48),dp(48)));
        themeUpdates.put(b,()->{icon.update(b==themeButton?(dark?ZenIcon.SUN:ZenIcon.MOON):kind,ink());});
        b.setOnClickListener(v->{if(!busy||kind==ZenIcon.LOCK)action.run();});return b;
    }
    private FrameLayout optionCard(OptionFlowLayout area) {
        FrameLayout frame=new FrameLayout(this);frame.setPadding(0,dp(8),0,0);
        area.addView(frame,new ViewGroup.LayoutParams(-2,-2));return frame;
    }
    private void compactOption(Button b) {
        b.setTextSize(13);b.setPadding(dp(10),dp(8),dp(10),dp(8));b.setMinWidth(dp(48));b.setMinimumWidth(dp(48));
        b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setIncludeFontPadding(false);
        b.setLayoutParams(new LinearLayout.LayoutParams(-2,-2));
    }
    private Button deleteCorner(FrameLayout frame,String entity,JSONObject option,Runnable refresh) {
        Button x=new Button(this);x.setText("×");x.setTextSize(14);x.setAllCaps(false);x.setMinWidth(0);x.setMinimumWidth(0);x.setMinHeight(0);x.setMinimumHeight(0);x.setPadding(0,0,0,0);
        GradientDrawable circle=new GradientDrawable();circle.setShape(GradientDrawable.OVAL);circle.setColor(popover());circle.setStroke(dp(1),border());
        android.graphics.drawable.LayerDrawable badge=new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{circle});badge.setLayerSize(0,dp(20),dp(20));badge.setLayerGravity(0,Gravity.TOP|Gravity.RIGHT);
        x.setBackground(badge);x.setGravity(Gravity.TOP|Gravity.RIGHT);x.setPadding(0,0,dp(5),0);x.setIncludeFontPadding(false);x.setTextColor(ink());x.setFilterTouchesWhenObscured(true);
        x.setContentDescription("删除"+(entity.equals("practiceType")?option.optString("name"):option.optInt("minutes")+" 分钟"));
        themeUpdates.put(x,()->{circle.setColor(popover());circle.setStroke(dp(1),border());x.setTextColor(ink());});
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP|Gravity.RIGHT);lp.topMargin=-dp(8);frame.setClipChildren(false);frame.setClipToPadding(false);frame.addView(x,lp);
        x.setOnClickListener(v->{if(busy||!unlocked())return;confirm("删除这个选项？","历史练习记录保留，选项可从已删除中恢复。",()->changeAsync(n->Records.delete(n,entity,option.optString("id")),refresh));});return x;
    }
    private void webStat(LinearLayout line,String label,String value,String detail) {
        LinearLayout card=column();card.setPadding(dp(10),dp(6),dp(10),dp(6));card.setMinimumHeight(dp(64));GradientDrawable background=bg(surface());card.setBackground(background);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(0,0,dp(8),dp(8));line.addView(card,lp);
        themeUpdates.put(card,()->{background.setColor(surface());background.setStroke(dp(1),border());});
        for(TextView v:new TextView[]{text(card,label,11),text(card,value,19),text(card,detail,10)}){v.setPadding(0,0,0,0);v.setLineSpacing(0,1.05f);}
    }
    private void today()throws Exception {
        LocalDate day=todayDate();PracticeStats stats=index().stats;
        TextView dayLabel=text(page,day.toString(),12);themeUpdates.put(dayLabel,()->dayLabel.setTextColor(accent()));dayLabel.setTextColor(accent());
        dayLabel.setPadding(0,0,0,dp(2));TextView todayHeading=text(page,"今日功课",24);todayHeading.setPadding(0,0,0,dp(2));TextView motto=text(page,"不争一时之速，只记每日之功。",12);motto.setPadding(0,0,0,dp(6));
        LinearLayout first=row(page);webStat(first,"今日时长",stats.todayMinutes+" 分钟",index().today.size()+" 次记录");webStat(first,"近 7 天",stats.weekMinutes+" 分钟","含今天");
        LinearLayout second=row(page);webStat(second,"累计时长",stats.totalMinutes+" 分钟",stats.totalCount+" 次练习");webStat(second,"连续打卡",stats.streak+" 天","今日或昨日仍可续上");
        LinearLayout form=recordCard();form.setPadding(dp(12),dp(8),dp(12),dp(12));
        TextView formTitle=text(form,"记一课",18);formTitle.setPadding(0,0,0,dp(2));TextView formHint=text(form,"选类型，定时长，立即落笔",12);formHint.setPadding(0,0,0,dp(4));
        JSONObject d=draft("checkIn");String[] type={d.optString("typeId")},date={d.optString("date",day.toString())},dateMode={d.optString("dateMode",d.has("date")?"legacy":"today")};
        if("today".equals(dateMode[0]))date[0]=todayDate().toString();
        Integer[] preset={d.has("preset")?d.getInt("preset"):null};
        Map<String,Button> typeButtons=new HashMap<>();List<Button> presets=new ArrayList<>();List<Integer> values=new ArrayList<>();
        LinearLayout typeTitle=row(form);typeTitle.setMinimumHeight(dp(32));TextView heading=text(typeTitle,"练习类型",14);heading.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        Button typeDone=button(typeTitle,"完成",()->leaveManagement(this::showApp));typeDone.setContentDescription("完成练习类型编辑");typeDone.setLayoutParams(new LinearLayout.LayoutParams(dp(64),dp(48)));
        OptionFlowLayout typeArea=new OptionFlowLayout(this);typeArea.setContentDescription("练习类型按钮区域");form.addView(typeArea,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout durationTitle=row(form);durationTitle.setMinimumHeight(dp(32));TextView durationHeading=text(durationTitle,"练习时长（分钟）",14);durationHeading.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        Button durationDone=button(durationTitle,"完成",()->leaveManagement(this::showApp));durationDone.setContentDescription("完成时长编辑");durationDone.setLayoutParams(new LinearLayout.LayoutParams(dp(64),dp(48)));
        OptionFlowLayout presetArea=new OptionFlowLayout(this);form.addView(presetArea,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout manualArea=column();form.addView(manualArea);
        EditText manual=field(manualArea,"任意分钟数",d.optString("manual"),false,false);manualArea.getChildAt(0).setVisibility(View.GONE);manual.setContentDescription("任意分钟数");manual.setHint("任意分钟数");manual.setInputType(InputType.TYPE_CLASS_NUMBER);manual.setMinHeight(dp(48));
        LinearLayout detailsArea=column();form.addView(detailsArea);
        boolean[] detailsOpen={!d.optString("note").isEmpty()||!"today".equals(dateMode[0])};
        detailsArea.setVisibility(detailsOpen[0]?View.VISIBLE:View.GONE);
        Button datePick=button(detailsArea,"练习日期 · "+date[0]+" ▾",()->chooseDay(date[0],indexMarks(false),"练习记录",chosen->{date[0]=chosen.toString();dateMode[0]="manual";try{JSONObject current=draft("checkIn");current.put("date",date[0]).put("dateMode","manual");saveDraft("checkIn",current);}catch(Exception e){fail(e);}showApp();}));
        datePick.setContentDescription("选择练习日期");
        TextView dateHint=text(detailsArea,"legacy".equals(dateMode[0])?"旧草稿日期："+date[0]+"。请选择继续补记，或改用今日。":"",12);dateHint.setVisibility("legacy".equals(dateMode[0])?View.VISIBLE:View.GONE);
        EditText note=field(detailsArea,"打卡备注（可选）",d.optString("note"),true,false);note.setHint("只属于这一次练习的简短备注");
        boolean[] adjusting={false};
        Runnable write=()->{try{JSONObject n=object("typeId",type[0],"manual",manual.getText().toString(),"date",date[0],"note",note.getText().toString(),"dateMode",dateMode[0]);if(preset[0]!=null)n.put("preset",preset[0]);saveDraft("checkIn",n);}catch(Exception e){fail(e);}};
        if("legacy".equals(dateMode[0]))button(detailsArea,"继续补记 "+date[0],()->{dateMode[0]="manual";write.run();dateHint.setVisibility(View.GONE);});
        button(detailsArea,"改用今日",()->{dateMode[0]="today";date[0]=todayDate().toString();datePick.setText("练习日期 · "+date[0]+" ▾");write.run();dateHint.setVisibility(View.GONE);});
        OptionFlowLayout actions=new OptionFlowLayout(this);form.addView(actions,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout detailsBody=column();actions.addView(detailsBody);Button details=button(detailsBody,detailsOpen[0]?"⌃ 收起补充项":"⌄ 补记日期或添加备注",()->{detailsOpen[0]=!detailsOpen[0];detailsArea.setVisibility(detailsOpen[0]?View.VISIBLE:View.GONE);});compactOption(details);
        details.setContentDescription("补记日期或添加备注");details.setOnClickListener(v->{if(busy)return;detailsOpen[0]=!detailsOpen[0];details.setText(detailsOpen[0]?"⌃ 收起补充项":"⌄ 补记日期或添加备注");detailsArea.setVisibility(detailsOpen[0]?View.VISIBLE:View.GONE);});
        List<Button> typeDeletes=new ArrayList<>(),durationDeletes=new ArrayList<>();
        Runnable showEditing=()->{for(Button x:typeDeletes)x.setVisibility("practiceType".equals(managedEntity)?View.VISIBLE:View.GONE);for(Button x:durationDeletes)x.setVisibility("durationPreset".equals(managedEntity)?View.VISIBLE:View.GONE);typeDone.setVisibility("practiceType".equals(managedEntity)?View.VISIBLE:View.GONE);durationDone.setVisibility("durationPreset".equals(managedEntity)?View.VISIBLE:View.GONE);};
        refreshPracticeOptions=()->{
            forgetTheme(typeArea);forgetTheme(presetArea);typeArea.removeAllViews();presetArea.removeAllViews();typeButtons.clear();presets.clear();values.clear();typeDeletes.clear();durationDeletes.clear();
            try{
                JSONObject pending=draft("checkIn");type[0]=pending.optString("typeId");preset[0]=pending.has("preset")?pending.getInt("preset"):null;
                adjusting[0]=true;if(!manual.getText().toString().equals(pending.optString("manual")))manual.setText(pending.optString("manual"));adjusting[0]=false;
                List<JSONObject> types=Records.options(data,"practiceType");
                if(Records.find(data,"practiceType",type[0])==null&&!types.isEmpty())type[0]=types.get(0).getString("id");
                for(JSONObject t:types){
                    String id=t.getString("id");FrameLayout card=optionCard(typeArea);card.setTag(id);LinearLayout body=column();card.addView(body,new FrameLayout.LayoutParams(-2,-2));
                    Button b=button(body,t.getString("name"),()->{if(managedEntity!=null)return;type[0]=id;for(String k:typeButtons.keySet())selected(typeButtons.get(k),k.equals(id));write.run();});
                    compactOption(b);typeButtons.put(id,b);selected(b,id.equals(type[0]));
                    Button x=deleteCorner(card,"practiceType",t,()->{saved("选项已移到已删除");if(refreshPracticeOptions!=null)refreshPracticeOptions.run();});typeDeletes.add(x);
                    float[] touch={0,0};
                    View.OnTouchListener dragTouch=(v,event)->{
                        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){touch[0]=event.getRawX();touch[1]=event.getRawY();}
                        if(!typeArea.isDragging())return false;
                        if(!unlocked()||busy){typeArea.cancelDrag();b.setPressed(false);v.setPressed(false);return true;}
                        if(event.getActionMasked()==MotionEvent.ACTION_MOVE){typeArea.dragTo(event.getRawX(),event.getRawY());return true;}
                        if(event.getActionMasked()==MotionEvent.ACTION_UP){List<String> ids=typeArea.finishDrag(true);b.setPressed(false);v.setPressed(false);if(ids!=null)changeAsync(n->Records.orderPracticeTypes(n,ids),()->{saved("练习顺序已保存");if(refreshPracticeOptions!=null)refreshPracticeOptions.run();},()->{diagnostics.record(Diagnostics.Code.ORDER_COMMIT_FAILED);if(refreshPracticeOptions!=null)refreshPracticeOptions.run();});return true;}
                        if(event.getActionMasked()==MotionEvent.ACTION_CANCEL){typeArea.cancelDrag();b.setPressed(false);v.setPressed(false);return true;}return true;
                    };b.setOnTouchListener(dragTouch);x.setOnTouchListener(dragTouch);
                    b.setOnLongClickListener(v->{if(!busy&&unlocked()){managedEntity="practiceType";showEditing.run();if(touch[0]==0&&touch[1]==0){int[] xy=new int[2];b.getLocationOnScreen(xy);touch[0]=xy[0]+b.getWidth()/2f;touch[1]=xy[1]+b.getHeight()/2f;}typeArea.beginDrag(card,touch[0],touch[1]);b.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);}return true;});
                    x.setOnLongClickListener(v->b.performLongClick());
                }
                FrameLayout add=optionCard(typeArea);LinearLayout addBody=column();add.addView(addBody,new FrameLayout.LayoutParams(-2,-2));Button plus=button(addBody,"＋",()->editOption("practiceType",null));compactOption(plus);plus.setContentDescription("添加练习类型");
                for(JSONObject option:Records.options(data,"durationPreset")){
                    int m=option.getInt("minutes");if(values.contains(m))continue;values.add(m);FrameLayout card=optionCard(presetArea);LinearLayout body=column();card.addView(body,new FrameLayout.LayoutParams(-2,-2));
                    Button b=button(body,m+" 分钟",()->{if(managedEntity!=null)return;preset[0]=Objects.equals(preset[0],m)?null:m;adjusting[0]=true;manual.setText("");adjusting[0]=false;for(int j=0;j<presets.size();j++)selected(presets.get(j),Objects.equals(values.get(j),preset[0]));write.run();});
                    compactOption(b);presets.add(b);selected(b,Objects.equals(preset[0],m));
                    Button x=deleteCorner(card,"durationPreset",option,()->{saved("选项已移到已删除");if(refreshPracticeOptions!=null)refreshPracticeOptions.run();});durationDeletes.add(x);
                    b.setOnLongClickListener(v->{if(!busy){managedEntity="durationPreset";showEditing.run();}return true;});
                }
                FrameLayout addDuration=optionCard(presetArea);LinearLayout addDurationBody=column();addDuration.addView(addDurationBody,new FrameLayout.LayoutParams(-2,-2));Button plusMinutes=button(addDurationBody,"＋",()->editOption("durationPreset",null));compactOption(plusMinutes);plusMinutes.setContentDescription("添加常用练习时长");
                showEditing.run();
            }catch(Exception e){adjusting[0]=false;fail(e);}
        };
        refreshPracticeOptions.run();
        watch(manual,()->{if(adjusting[0])return;preset[0]=null;for(Button b:presets)selected(b,false);write.run();});watch(note,write);
        LinearLayout submitBody=column();actions.addView(submitBody);
        Button submit=button(submitBody,"✓ 完成打卡",()->{
            try{
                JSONObject t=Records.find(data,"practiceType",type[0]);
                if(t==null){heading.setError("请选择一种练习类型");saved("请先选择一种练习类型。");return;}
                int minutes;
                try{minutes=preset[0]!=null?preset[0]:Records.minutes(manual.getText().toString());}
                catch(IllegalArgumentException e){manual.setError("请输入 1–1440 的整数分钟。");manual.requestFocus();return;}
                if("legacy".equals(dateMode[0])){saved("请先确认旧草稿日期：继续补记，或改用今日。");return;}
                if("today".equals(dateMode[0]))date[0]=todayDate().toString();
                try{Records.date(date[0]);}catch(IllegalArgumentException e){saved("请通过补记日期重新选择有效日期。");return;}
                if(note.length()>50000){note.setError("备注最多 50000 个字符。");return;}
                JSONObject r=object("id",Records.id(),"practiceTypeId",type[0],"practiceTypeName",t.getString("name"),"practiceDate",date[0],"note",note.getText().toString()).put("durationMinutes",minutes);
                changeAsync(n->{r.put("createdAt",Records.now());Records.upsert(n,"checkIn",r);n.getJSONObject("drafts").remove("checkIn");},()->{managedEntity=null;saved("今日一课，已记下");scrollOffsets[0]=0;if(contentScroll!=null)contentScroll.scrollTo(0,0);showApp();});
            }catch(Exception e){fail(e);}
        });compactOption(submit);submit.setMinWidth(dp(128));submit.setContentDescription("保存练习");selected(submit,true);
    }

    private void openManagement(String entity){
        if(!unlocked()||busy)return;managedEntity=entity;orderDirty=false;typeOrder.clear();tab=0;showApp();
    }
    private void leaveManagement(Runnable after){
        if(busy)return;managedEntity=null;typeOrder.clear();orderDirty=false;after.run();
    }
    private void statistics()throws Exception{
        text(page,"数据",28);PracticeStats stats=index().stats;text(page,"练习统计",20);text(page,"近 7 天 "+stats.weekMinutes+" 分钟 · 连续 "+stats.streak+" 天\n累计 "+stats.totalMinutes+" 分钟 · "+stats.totalCount+" 次",16);heatmaps(stats.minutesByDate);
    }
    private void heatmaps(Map<String,Long> minutes){
        text(page,"练习热力图",23);
        LinearLayout month=row(page);text(month,"月",20);
        Button monthPick=button(month,heatmapMonth.getYear()+" 年 "+heatmapMonth.getMonthValue()+" 月 ▾",this::chooseHeatmapMonth);
        monthPick.setContentDescription("选择统计月份");
        List<PracticeHeatmap.Day> monthly=PracticeHeatmap.month(heatmapMonth.getYear(),heatmapMonth.getMonthValue(),minutes);
        periodSummary(monthly);heatmapGrid(monthly,7,true);
        LinearLayout annual=row(page);text(annual,"全年",20);Button year=button(annual,heatmapYear+" 年 ▾",this::chooseHeatmapYear);year.setContentDescription("选择统计年份");
        List<PracticeHeatmap.Day> yearly=PracticeHeatmap.year(heatmapYear,minutes);periodSummary(yearly);heatmapGrid(yearly,19,false);
        text(page,"黄色越浓，实际练习时长越长。",13);
        int[] colors=new int[240];for(int i=0;i<colors.length;i++)colors[i]=PracticeHeatmap.color(i+1,dark);
        View legend=new View(this);legend.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,colors));legend.setContentDescription("连续黄色色阶：15、30、60、120、240 分钟；240 分钟及以上为最浓黄色");
        page.addView(legend,new LinearLayout.LayoutParams(-1,dp(16)));
        text(page,"参考：15 · 30 · 60 · 120 · 240 分钟\n灰色为 0 分钟；1–240 分钟连续渐变，240 分钟以上保持最浓黄色。",13);
    }
    private void periodSummary(List<PracticeHeatmap.Day> days){
        long sum=0;int active=0;for(PracticeHeatmap.Day d:days){sum+=d.minutes;if(d.minutes>0)active++;}
        text(page,"实际练习 "+sum+" 分钟 · "+active+" 天",14);
    }
    private NumberPicker yearPicker(LinearLayout parent,int value){
        NumberPicker picker=new NumberPicker(dialogContext());picker.setMinValue(1);picker.setMaxValue(9999);picker.setValue(value);picker.setWrapSelectorWheel(false);picker.setSaveEnabled(false);
        picker.setContentDescription("年份，可输入 1 至 9999");parent.addView(picker,new LinearLayout.LayoutParams(-1,dp(144)));return picker;
    }
    private void chooseHeatmapMonth(){
        chooseMonth(heatmapMonth,month->{heatmapMonth=month;showApp();});
    }
    private void chooseHeatmapYear(){
        if(!unlocked()||busy)return;
        long token=generation;LinearLayout form=column();NumberPicker picker=yearPicker(form,heatmapYear);
        AlertDialog d=new AlertDialog.Builder(dialogContext()).setTitle("选择年份").setView(form).setNegativeButton("取消",null).setPositiveButton("确定",null).create();dialog(d);
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(!unlocked()||busy||token!=generation)return;picker.clearFocus();heatmapYear=picker.getValue();d.dismiss();showApp();});
    }
    private void heatmapGrid(List<PracticeHeatmap.Day> days,int columns,boolean calendar){
        if(calendar){LinearLayout weekdays=row(page);for(String label:new String[]{"一","二","三","四","五","六","日"}){
            TextView v=new TextView(this);v.setText(label);v.setTextColor(ink());v.setGravity(Gravity.CENTER);
            weekdays.addView(v,new LinearLayout.LayoutParams(0,dp(28),1));themeUpdates.put(v,()->v.setTextColor(ink()));
        }}
        HeatmapView grid=new HeatmapView(this,days,columns,calendar,dark,null);
        page.addView(grid,new LinearLayout.LayoutParams(-1,-2));themeUpdates.put(grid,()->grid.theme(dark));
    }
    private void info(String title,String message){AlertDialog d=new AlertDialog.Builder(dialogContext()).setTitle(title).setMessage(message).setPositiveButton("关闭",null).create();dialog(d);}
    private String preview(String text){return text.length()>600?text.substring(0,600)+"…":text;}
    private List<JSONObject> sorted(String array,String date)throws Exception{return array.equals("journals")?index().journalRows:index().history;}
    private void remove(String entity,String id){confirm("移到已删除？","可在设置 → 备份 → 已删除中恢复。完整加密备份仍保留这些内容。",()->changeAsync(d->Records.delete(d,entity,id),()->{saved("记录已移到已删除，可恢复。");showApp();}));}
    private void dateFilter(boolean journal){
        String chosen=journal?journalFilter:historyDate;text(page,chosen==null?"最近 7 天 · 含今天":chosen,14);
        LinearLayout controls=row(page);button(controls,"选择日期",()->dateCalendar(journal));if(chosen!=null)button(controls,"最近 7 天",()->{if(journal){journalFilter=null;journalPage=0;}else{historyDate=null;historyPage=0;}showApp();});
    }
    private Map<String,Long> indexMarks(boolean journal){
        try{return journal?index().journalDays:index().stats.minutesByDate;}catch(Exception e){fail(e);return Collections.emptyMap();}
    }
    private void dateCalendar(boolean journal){
        String chosen=journal?journalFilter:historyDate;
        chooseDay(chosen,indexMarks(journal),journal?"感悟":"练习记录",day->{
            if(journal){journalFilter=day.toString();journalPage=0;}else{historyDate=day.toString();historyPage=0;}
            showApp();
        });
    }
    private void chooseMonth(java.time.YearMonth selected,java.util.function.Consumer<java.time.YearMonth> accept){
        long token=generation;
        LinearLayout form=column();form.setPadding(dp(12),dp(8),dp(12),dp(12));
        NumberPicker year=yearPicker(form,selected.getYear());ScrollView s=new ScrollView(this);s.setSaveEnabled(false);s.addView(form);
        AlertDialog d=new AlertDialog.Builder(dialogContext()).setTitle("选择年月").setView(s).setNegativeButton("取消",null).create();
        LinearLayout months=column();form.addView(months);
        Map<Integer,Button> buttons=new HashMap<>();
        for(int i=1;i<=12;i++){
            final int m=i;LinearLayout line=i%3==1?row(months):(LinearLayout)months.getChildAt(months.getChildCount()-1);
            Button b=button(line,i+" 月",()->{if(!unlocked()||token!=generation)return;year.clearFocus();java.time.YearMonth result=java.time.YearMonth.of(year.getValue(),m);d.dismiss();accept.accept(result);});buttons.put(i,b);selected(b,i==selected.getMonthValue());
        }
        year.setOnValueChangedListener((p,old,next)->{for(Map.Entry<Integer,Button> b:buttons.entrySet())selected(b.getValue(),next==selected.getYear()&&b.getKey()==selected.getMonthValue());});
        dialog(d);
    }
    private void chooseDay(String chosen,Map<String,Long> marks,String marker,java.util.function.Consumer<LocalDate> accept){
        if(!unlocked()||busy)return;
        long token=generation;
        LocalDate initial;try{initial=chosen==null?todayDate():LocalDate.parse(chosen);}catch(Exception e){initial=todayDate();}
        java.time.YearMonth[] month={java.time.YearMonth.from(initial)};
        LinearLayout form=column();form.setPadding(dp(12),dp(8),dp(12),dp(12));
        ScrollView s=new ScrollView(this);s.setSaveEnabled(false);s.addView(form);
        LinearLayout calendar=column();form.addView(calendar);
        AlertDialog d=new AlertDialog.Builder(dialogContext()).setTitle("选择日期 · 有"+marker+"的日子着色").setView(s).setNegativeButton("关闭",null).create();
        Runnable[] render=new Runnable[1];
        render[0]=()->{
            forgetTheme(calendar);calendar.removeAllViews();LinearLayout controls=row(calendar);
            Button previous=button(controls,"‹",()->{month[0]=month[0].minusMonths(1);render[0].run();});previous.setContentDescription("上个月");previous.setEnabled(month[0].getYear()>1||month[0].getMonthValue()>1);
            Button title=button(controls,month[0].getYear()+" 年 "+month[0].getMonthValue()+" 月 ▾",()->chooseMonth(month[0],m->{month[0]=m;render[0].run();}));title.setContentDescription("选择日期年月");title.setLayoutParams(new LinearLayout.LayoutParams(0,-2,3));
            Button next=button(controls,"›",()->{month[0]=month[0].plusMonths(1);render[0].run();});next.setContentDescription("下个月");next.setEnabled(month[0].getYear()<9999||month[0].getMonthValue()<12);
            LinearLayout weekdays=row(calendar);for(String label:new String[]{"一","二","三","四","五","六","日"}){TextView v=text(weekdays,label,12);v.setGravity(Gravity.CENTER);v.setLayoutParams(new LinearLayout.LayoutParams(0,dp(30),1));v.setPadding(0,0,0,0);}
            HeatmapView grid=new HeatmapView(this,PracticeHeatmap.month(month[0].getYear(),month[0].getMonthValue(),marks),7,true,dark,day->{if(!unlocked()||busy||token!=generation)return;d.dismiss();accept.accept(day.date);});
            grid.markers(marker);calendar.addView(grid,new LinearLayout.LayoutParams(-1,-2));themeUpdates.put(grid,()->grid.theme(dark));
            button(calendar,"按日期列表选择",()->{
                LinearLayout list=column();list.setPadding(dp(12),dp(8),dp(12),dp(12));ScrollView listScroll=new ScrollView(this);listScroll.setSaveEnabled(false);listScroll.addView(list);
                AlertDialog dates=new AlertDialog.Builder(dialogContext()).setTitle(month[0].getYear()+" 年 "+month[0].getMonthValue()+" 月").setView(listScroll).setNegativeButton("返回月历",null).create();
                for(int day=1;day<=month[0].lengthOfMonth();day++){LocalDate target=month[0].atDay(day);Button b=button(list,day+" 日"+(marks.getOrDefault(target.toString(),0L)>0?" · 有"+marker:""),()->{if(!unlocked()||token!=generation)return;dates.dismiss();d.dismiss();accept.accept(target);});b.setContentDescription(target+"，"+(marks.getOrDefault(target.toString(),0L)>0?"有":"无")+marker);}
                dialog(dates);
            });
        };
        render[0].run();dialog(d);
    }
    private LinearLayout recordCard(){
        LinearLayout card=column();card.setPadding(dp(16),dp(4),dp(16),dp(12));GradientDrawable background=bg(surface());card.setBackground(background);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(6),0,dp(12));page.addView(card,lp);themeUpdates.put(card,()->{background.setColor(surface());background.setStroke(dp(1),border());});return card;
    }
    private void history()throws Exception{
        text(page,"练习记录",22);text(page,"按日期归拢，每日时长一目了然。",13);dateFilter(false);
        RecordIndex idx=index();List<JSONObject> rows=historyDate==null?idx.recentChecks:idx.checksByDay.getOrDefault(historyDate,Collections.emptyList());
        LinkedHashMap<String,List<JSONObject>> groups=new LinkedHashMap<>();for(JSONObject r:rows)groups.computeIfAbsent(r.optString("practiceDate"),k->new ArrayList<>()).add(r);
        List<String> days=new ArrayList<>(groups.keySet());historyPage=Math.min(historyPage,Math.max(0,(days.size()-1)/PAGE_SIZE));
        for(String day:days.subList(historyPage*PAGE_SIZE,Math.min((historyPage+1)*PAGE_SIZE,days.size()))){
            List<JSONObject> entries=groups.get(day);LinearLayout card=recordCard();LinearLayout title=row(card);TextView date=text(title,day,18);date.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));long subtotal=0;for(JSONObject r:entries)subtotal+=r.optInt("durationMinutes");text(title,"小计 "+subtotal+" 分钟",14);text(card,entries.size()+" 次练习",12);
            for(JSONObject r:entries){
                LinearLayout line=row(card);line.setGravity(Gravity.CENTER_VERTICAL);LinearLayout body=column();line.addView(body,new LinearLayout.LayoutParams(0,-2,1));text(body,r.optString("practiceTypeName"),16);
                if(Records.find(data,"practiceType",r.optString("practiceTypeId"))==null)text(body,"历史类型",11);
                if(!r.optString("note").isEmpty())text(body,r.optString("note"),14);
                text(line,r.optInt("durationMinutes")+" 分钟",14);headerIcon(line,ZenIcon.DELETE,"删除打卡记录",()->remove("checkIn",r.optString("id")));
            }
        }
        if(rows.isEmpty())text(page,"这段时间尚无练习记录。",16);
        if(days.size()>PAGE_SIZE)pageControls(days.size(),historyPage,n->{historyPage=n;showApp();});
    }
    private void openJournal(String day){if(!unlocked()||busy)return;journalDate=day;journalEditing=true;if(contentScroll!=null)contentScroll.scrollTo(0,0);scrollOffsets[2]=0;scrollOffsets[7]=0;showApp();}
    private void recordMenu(LinearLayout card,String entity,JSONObject r){
        LinearLayout line=row(card);Button more=button(line,"更多",()->{
            String[] labels=entity.equals("journal")?new String[]{"编辑感悟","移到已删除"}:new String[]{"编辑练习","移到已删除"};
            AlertDialog d=new AlertDialog.Builder(dialogContext()).setTitle("记录操作").setItems(labels,(x,which)->{
                if(which==0){if(entity.equals("journal"))openJournal(r.optString("journalDate"));else editCheckIn(r);}else remove(entity,r.optString("id"));
            }).setNegativeButton("取消",null).create();dialog(d);
        });more.setContentDescription("更多记录操作");more.setLayoutParams(new LinearLayout.LayoutParams(dp(80),dp(48)));
    }
    private void journalDetails(JSONObject r){
        AlertDialog d=new AlertDialog.Builder(dialogContext()).setTitle(r.optString("journalDate")+" 感悟")
            .setMessage("首次保存 · "+RecordTime.label(r)+"\n\n"+r.optString("content"))
            .setNegativeButton("关闭",null).setPositiveButton("编辑",(x,w)->openJournal(r.optString("journalDate"))).create();dialog(d);
    }
    private void journals()throws Exception{
        if(!journalEditing)journalDate=todayDate().toString();
        text(page,"练习感悟",22);text(page,"日记独立于打卡备注，同一天反复保存即更新原文。",13);
        journalEditor();
        text(page,"历史日记",20);dateFilter(true);
        List<String> unfinished=writer().journalDraftDays();if(!unfinished.isEmpty())button(page,"未完成草稿 "+unfinished.size(),this::showJournalDrafts);
        RecordIndex idx=index();List<JSONObject> rows=journalFilter==null?idx.recentJournals:idx.journalsByDay.getOrDefault(journalFilter,Collections.emptyList());journalPage=Math.min(journalPage,Math.max(0,(rows.size()-1)/PAGE_SIZE));
        for(JSONObject r:rows.subList(journalPage*PAGE_SIZE,Math.min((journalPage+1)*PAGE_SIZE,rows.size()))){
            String dateValue=r.getString("journalDate");LinearLayout card=recordCard(),line=row(card);LinearLayout entry=column();line.addView(entry,new LinearLayout.LayoutParams(0,-2,1));
            text(entry,dateValue,18);TextView summary=text(entry,r.getString("content"),14);summary.setMaxLines(2);summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
            TextView full=text(card,r.getString("content"),16);full.setVisibility(View.GONE);
            entry.setContentDescription("展开感悟 "+dateValue);entry.setFocusable(true);entry.setOnClickListener(v->{if(!unlocked()||busy)return;boolean expand=full.getVisibility()!=View.VISIBLE;full.setVisibility(expand?View.VISIBLE:View.GONE);summary.setVisibility(expand?View.GONE:View.VISIBLE);entry.setContentDescription((expand?"收起感悟 ":"展开感悟 ")+dateValue);});
            headerIcon(line,ZenIcon.EDIT,"编辑这篇日记",()->openJournal(dateValue));headerIcon(line,ZenIcon.DELETE,"删除这篇日记",()->remove("journal",r.optString("id")));
        }
        if(rows.isEmpty())text(page,"这段时间尚无感悟。",16);
        if(rows.size()>PAGE_SIZE)pageControls(rows.size(),journalPage,n->{journalPage=n;showApp();});
    }
    private void journalEditor()throws Exception{
        LinearLayout editor=recordCard();text(editor,"写下今日所得",20);
        JSONObject current=Records.journal(data,journalDate);String key="journal:"+journalDate;JSONObject d=draft(key);
        Button date=button(editor,journalDate+" ▾",()->chooseDay(journalDate,indexMarks(true),"感悟",chosen->openJournal(chosen.toString())));date.setContentDescription("选择感悟日期");
        Button discard=button(editor,"放弃未提交修改",()->discardJournalDraft(journalDate));discard.setVisibility(writer().journalDraftDays().contains(journalDate)?View.VISIBLE:View.GONE);
        EditText content=field(editor,"正文",d.has("content")?d.getString("content"):current==null?"":current.getString("content"),true,false);content.setMinHeight(dp(200));content.setHint("练习时身体有何感受？心境有何变化？如实写下即可……");
        text(editor,"草稿自动暂存本机，点击保存后成为正式记录。",12);
        Button save=button(editor,current==null?"保存此页":"更新此页",()->{
            try{
                if(content.getText().toString().trim().isEmpty()||content.length()>50000){content.setError("请填写正文，最多 50000 个字符。");content.requestFocus();return;}
                JSONObject r=current==null?object("id",Records.id()):Records.copy(current);String savedDay=journalDate;r.put("journalDate",savedDay).put("content",content.getText().toString());
                changeAsync(n->{if(current==null)r.put("createdAt",Records.now());r.put("updatedAt",Records.now());Records.upsert(n,"journal",r);n.getJSONObject("drafts").remove(key);},()->{journalPage=0;scrollOffsets[2]=0;scrollOffsets[7]=0;if(contentScroll!=null)contentScroll.scrollTo(0,0);saved("感悟已保存 · "+savedDay);showApp();});
            }catch(Exception e){fail(e);}
        });save.setContentDescription("保存感悟");selected(save,true);save.setEnabled(!content.getText().toString().trim().isEmpty());
        watch(content,()->{try{saveDraft(key,object("content",content.getText().toString()));discard.setVisibility(View.VISIBLE);save.setEnabled(!content.getText().toString().trim().isEmpty());}catch(Exception e){fail(e);}});
    }
    private String capacityLabel(){
        String used=String.format(Locale.ROOT,"%.1f",vaultBytes/(1024.0*1024));
        String prefix=vaultBytes>=VaultCrypto.MAX_PLAINTEXT*0.9?"资料库接近上限，请先导出并验证完整备份。 ":vaultBytes>=VaultCrypto.MAX_PLAINTEXT*0.8?"资料库占用已超过 80%。 ":"";
        return prefix+"已保存资料约 "+used+" / 16 MiB；操作历史、草稿、已删除和旧归档均计入。";
    }
    private void showJournalDrafts(){
        try{
            List<String> days=writer().journalDraftDays();
            AlertDialog d=new AlertDialog.Builder(dialogContext()).setTitle("未完成感悟草稿")
                .setItems(days.toArray(new String[0]),(x,i)->{tab=2;openJournal(days.get(i));}).setNegativeButton("关闭",null).create();dialog(d);
        }catch(Exception e){fail(e);}
    }
    private void discardJournalDraft(String day){
        String key="journal:"+day;
        confirm("放弃未提交修改？","只移除 "+day+" 的草稿。最后正式保存的感悟保持不变；新草稿不会变成空白感悟。",()->
            changeAsync(n->n.getJSONObject("drafts").remove(key),()->{saved("未提交修改已放弃");showApp();}));
    }
    private void editCheckIn(JSONObject old){
        try{
            JSONObject edited=Records.copy(old);String[] selectedDay={old.getString("practiceDate")};
            LinearLayout form=column();form.setPadding(dp(18),dp(10),dp(18),dp(10));
            text(form,"首次打卡时间保持不变。原类型已删除时，仍可保留历史名称。",13);
            Button[] typeButton=new Button[1];typeButton[0]=button(form,old.optString("practiceTypeName"),()->{
                try{
                    List<JSONObject> options=Records.options(data,"practiceType");String[] names=new String[options.size()+1];names[0]="保留原类型 · "+old.optString("practiceTypeName");for(int i=0;i<options.size();i++)names[i+1]=options.get(i).optString("name");
                    AlertDialog pick=new AlertDialog.Builder(dialogContext()).setTitle("练习类型").setItems(names,(x,i)->{
                        try{if(i==0){if(old.has("practiceTypeId"))edited.put("practiceTypeId",old.get("practiceTypeId"));else edited.remove("practiceTypeId");edited.put("practiceTypeName",old.getString("practiceTypeName"));}
                        else{JSONObject option=options.get(i-1);edited.put("practiceTypeId",option.getString("id")).put("practiceTypeName",option.getString("name"));}typeButton[0].setText(edited.optString("practiceTypeName"));}catch(Exception e){fail(e);}
                    }).setNegativeButton("取消",null).create();dialog(pick);
                }catch(Exception e){fail(e);}
            });
            Button[] dateButton=new Button[1];dateButton[0]=button(form,"练习日期 · "+selectedDay[0],()->chooseDay(selectedDay[0],indexMarks(false),"练习记录",day->{selectedDay[0]=day.toString();dateButton[0].setText("练习日期 · "+selectedDay[0]);}));
            EditText minutes=field(form,"时长（分钟）",old.optString("durationMinutes"),false,false);minutes.setInputType(InputType.TYPE_CLASS_NUMBER);
            EditText note=field(form,"备注（可选）",old.optString("note"),true,false);
            ScrollView scroll=new ScrollView(this);scroll.setSaveEnabled(false);scroll.addView(form);
            AlertDialog d=new AlertDialog.Builder(dialogContext()).setTitle("编辑练习").setView(scroll).setNegativeButton("取消",null).setPositiveButton("保存修改",null).create();dialog(d);
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                if(busy||!unlocked())return;
                try{
                    int m;try{m=Records.minutes(minutes.getText().toString());}catch(IllegalArgumentException error){minutes.setError("请输入 1–1440 的整数分钟。");return;}
                    if(note.length()>50000){note.setError("备注最多 50000 个字符。");return;}
                    edited.put("practiceDate",selectedDay[0]).put("durationMinutes",m).put("note",note.getText().toString());
                    d.setCancelable(false);activeAction=d.getButton(AlertDialog.BUTTON_POSITIVE);
                    changeAsync(n->{edited.put("updatedAt",Records.now());Records.upsert(n,"checkIn",edited);},()->{
                        d.dismiss();if(historyDate!=null)historyDate=selectedDay[0];historyPage=0;scrollOffsets[1]=0;saved("练习修改已保存");showApp();
                    },()->{d.setCancelable(true);d.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);});activeAction=null;
                }catch(Exception e){fail(e);}
            });
        }catch(Exception e){fail(e);}
    }
    private void editOption(String entity,JSONObject old){
        LinearLayout form=column();form.setPadding(dp(18),dp(10),dp(18),dp(10));
        boolean type=entity.equals("practiceType");
        EditText value=field(form,type?"练习类型名称":"常用分钟数",old==null?"":type?old.optString("name"):old.optString("minutes"),false,false);
        if(!type)value.setInputType(InputType.TYPE_CLASS_NUMBER);
        AlertDialog d=new AlertDialog.Builder(dialogContext()).setTitle(old==null?"新增":"修改").setView(form).setNegativeButton("取消",null).setPositiveButton("保存",null).create();dialog(d);
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(busy||!unlocked())return;
            try{
                String label=value.getText().toString().trim();
                if(type&&(label.isEmpty()||label.length()>60)){value.setError("请填写名称，最多 60 个字符。");return;}
                if(!type){try{Records.minutes(label);}catch(IllegalArgumentException e){value.setError("请输入 1–1440 的整数分钟。");return;}}
                JSONObject r=old==null?object("id",Records.id(),"createdAt",Records.now()):Records.copy(old);
                if(type)r.put("name",label);else r.put("minutes",Records.minutes(label)).put("label",label+" 分钟");
                r.put("updatedAt",Records.now()).put("sortOrder",old==null?data.getJSONArray(Records.array(entity)).length():old.optInt("sortOrder"));
                JSONArray options=data.getJSONArray(Records.array(entity));
                for(int i=0;i<options.length();i++){JSONObject o=options.getJSONObject(i);if(!o.getString("id").equals(r.getString("id"))&&(type?o.getString("name").equals(label):o.getInt("minutes")==r.getInt("minutes"))){value.setError(type?"已有这个名称。":"已有这个时长。");return;}}
                saveOptionAsync(d,value,entity,r,old==null&&tab==0&&managedEntity==null);
            }catch(Exception e){fail(e);}
        });
    }
    private void saveOptionAsync(AlertDialog dialog,EditText value,String entity,JSONObject record,boolean selectForPractice){
        value.setEnabled(false);dialog.setCancelable(false);
        Button save=dialog.getButton(AlertDialog.BUTTON_POSITIVE);save.setEnabled(false);save.setText("保存中…");
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
        changeAsync(n->{Records.upsert(n,entity,record);if(selectForPractice){JSONObject check=n.getJSONObject("drafts").optJSONObject("checkIn");if(check==null)check=new JSONObject();if(entity.equals("practiceType"))check.put("typeId",record.getString("id"));else{check.put("preset",record.getInt("minutes"));check.remove("manual");}n.getJSONObject("drafts").put("checkIn",check);}},()->{
            dialog.dismiss();saved("选项已保存");if(tab==0&&managedEntity==null&&refreshPracticeOptions!=null)refreshPracticeOptions.run();else showApp();
        },()->{
            value.setEnabled(true);dialog.setCancelable(true);save.setEnabled(true);save.setText("保存");dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
        });
    }
    private void settings()throws Exception{
        text(page,"设置",24);
        text(page,"解锁方式",19);
        button(page,biometric.enabled()?"重新设置指纹解锁":"启用指纹解锁",()->authenticateBiometric(true));
        if(biometric.enabled())button(page,"停用指纹解锁",()->{
            background(()->{biometric.disable();return true;},ok->{toast("已停用，之后使用主密码解锁");showApp();},Diagnostics.Code.BIOMETRIC_UNAVAILABLE);
        });
        text(page,"系统中已登记的强生物识别可解锁。新增指纹后需重新启用；请保留主密码。",14);
        button(page,"更换主密码",this::changePassword);
        text(page,"不联网、不遥测、不接入 AI；没有密码找回服务。\n系统截图、自动备份和应用最近任务预览已关闭。\n手机系统或输入法被攻破时，本应用无法保证隐私。",14);
        text(page,"备份",19);
        text(page,"导出、验证与恢复加密备份，以及旧网页资料迁移。",14);
        Button backups=button(page,"备份与导出",()->{backupOrigin=5;tab=4;showApp();});backups.setContentDescription("备份");
    }
    private void showDiagnostics(){
        if(!unlocked())return;
        String report=diagnostics.report();
        AlertDialog d=new AlertDialog.Builder(dialogContext()).setTitle("本地诊断")
            .setMessage("最多保留最近 30 分钟内的 32 条错误码，仅在本次页面实例内存中；重启或页面重建即清空。不自动上传。\n\n"+report)
            .setNegativeButton("关闭",null).setNeutralButton("清空",(x,w)->{diagnostics.clear();diagnosticExport=null;toast("诊断已清空");})
            .setPositiveButton("导出此报告",(x,w)->confirm("导出诊断报告？",report+"\n以上是文件的全部内容。请选可信本地目录；文件会保留到你手动删除，不会自动发送给开发者。",()->{
                diagnosticExport=report;pick(DIAGNOSTICS);
            })).create();
        dialog(d);
    }
    /** Cipher preparation, Keystore wrapping and backup parsing stay off the UI. */
    @android.annotation.TargetApi(28)
    private void authenticateBiometric(boolean enroll){
        if(android.os.Build.VERSION.SDK_INT<28){toast("此版本系统请使用主密码");return;}
        if(enroll&&!unlocked())return;
        passwordRevision++;if(passwordDebounce!=null)unlockHandler.removeCallbacks(passwordDebounce);
        long token=generation;setBusy(true);inputsEnabled(false);
        crypto.execute(()->{
            javax.crypto.Cipher prepared=null;Exception error=null;
            try{prepared=enroll?biometric.enrollmentCipher():biometric.unlockCipher();}catch(Exception e){error=e;}
            javax.crypto.Cipher cipher=prepared;Exception problem=error;
            runOnUiThread(()->{
                if(token!=generation||isFinishing())return;
                if(problem!=null){biometricFallback();diagnostics.record(Diagnostics.Code.BIOMETRIC_UNAVAILABLE);toast("无法使用指纹，请用主密码解锁。");return;}
                try{
                biometricCancel=new android.os.CancellationSignal();
                android.hardware.biometrics.BiometricPrompt.Builder builder=new android.hardware.biometrics.BiometricPrompt.Builder(this)
                    .setTitle(enroll?"启用日课指纹解锁":"解锁日课").setSubtitle("验证仅由手机系统完成")
                    .setNegativeButton(enroll?"取消":"使用主密码",getMainExecutor(),(d,w)->{if(token==generation)biometricFallback();});
                if(android.os.Build.VERSION.SDK_INT>=29)builder.setConfirmationRequired(false);
                if(android.os.Build.VERSION.SDK_INT>=30)builder.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG);
                builder.build().authenticate(new android.hardware.biometrics.BiometricPrompt.CryptoObject(cipher),biometricCancel,getMainExecutor(),new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback(){
                    @Override public void onAuthenticationError(int code,CharSequence message){if(token!=generation)return;biometricFallback();}
                    @Override public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult result){
                        if(token!=generation||isFinishing())return;biometricCancel=null;
                        if(result.getCryptoObject()==null||result.getCryptoObject().getCipher()==null){biometricFallback();return;}
                        javax.crypto.Cipher authenticated=result.getCryptoObject().getCipher();
                        VaultCrypto.Session owner=enroll&&unlocked()?VaultCrypto.duplicate(session):null;
                        crypto.execute(()->{
                            VaultCrypto.Opened opened=null;JSONObject parsed=null;RecordIndex indexed=null;Exception error=null;
                            try{
                                if(enroll){if(owner==null)throw new IllegalStateException();biometric.enroll(authenticated,owner);}
                                else{opened=biometric.open(authenticated,store.read());parsed=Records.validate(new JSONObject(new String(opened.plaintext,StandardCharsets.UTF_8)));vaultBytes=opened.plaintext.length;indexed=buildIndex(parsed);}
                            }catch(Exception e){error=e;}finally{if(owner!=null)owner.close();if(opened!=null)Arrays.fill(opened.plaintext,(byte)0);}
                            VaultCrypto.Opened got=opened;JSONObject dataResult=parsed;RecordIndex indexResult=indexed;Exception failure=error;
                            runOnUiThread(()->{
                                if(token!=generation||isFinishing()){if(got!=null)got.session.close();return;}
                                setBusy(false);inputsEnabled(true);
                                if(failure!=null){if(got!=null)got.session.close();biometricFallback();diagnostics.record(Diagnostics.Code.BIOMETRIC_UNAVAILABLE);toast("指纹密钥不可用，请用主密码解锁后重新启用。");return;}
                                if(enroll){toast("已启用指纹解锁");showApp();}
                                else{cancelAutomaticUnlock();wipe(root);session=got.session;data=dataResult;recordIndex=indexResult;showApp();consumePending();}
                            });
                        });
                    }
                });
                }catch(Exception e){biometricFallback();diagnostics.record(Diagnostics.Code.BIOMETRIC_UNAVAILABLE);toast("无法使用指纹，请用主密码解锁。");}
            });
        });
    }
    private void changePassword(){
        LinearLayout form=column();form.setPadding(dp(18),dp(10),dp(18),dp(10));
        EditText p=field(form,"新主密码（不限制最短长度）","",false,true),again=field(form,"再次输入","",false,true);
        passwordKeyboard(form,p,again);
        text(form,"短密码容易被猜解，建议使用较长的随机口令。",14);
        text(form,BuildConfig.DEBUG?"旧备份仍使用旧密码。":"恢复密钥不变。旧备份仍使用旧密码。",14);
        AlertDialog d=new AlertDialog.Builder(dialogContext()).setTitle("更换主密码").setView(form).setNegativeButton("取消",null).setPositiveButton("保存",null).create();dialog(d);
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(busy)return;
            String password=p.getText().toString();if(!password.equals(again.getText().toString())){toast("两次密码不一致");return;}
            try{VaultCrypto.requirePassword(password.toCharArray());}catch(Exception e){fail(e);return;}
            long ticket=generation;VaultCrypto.Session original=session;setBusy(true);p.setText("");again.setText("");
            crypto.execute(()->{
                VaultCrypto.Session replacement=null;Exception error=null;char[] chars=password.toCharArray();
                try{replacement=VaultCrypto.changePassword(original,chars);}catch(Exception e){error=e;}finally{Arrays.fill(chars,'\0');}
                VaultCrypto.Session next=replacement;Exception problem=error;
                runOnUiThread(()->{
                    if(ticket!=generation||!unlocked()){if(next!=null)next.close();return;}setBusy(false);
                    // Cancelling this dialog must cancel the pending password change.
                    if(!d.isShowing()){if(next!=null)next.close();return;}
                    if(problem!=null){fail(problem);return;}
                    setBusy(true);inputsEnabled(false);
                    crypto.execute(()->{
                        try{biometric.disable();}catch(Exception e){runOnUiThread(()->{if(ticket==generation){setBusy(false);inputsEnabled(true);fail(e);}next.close();});return;}
                        // Actor preserves all drafts/queued records when changing only the key header.
                        runOnUiThread(()->{
                            if(ticket!=generation||!unlocked()||!d.isShowing()){if(ticket==generation){setBusy(false);inputsEnabled(true);}next.close();return;}
                            d.setCancelable(false);d.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                            writer().rekey(next,(saved,commitFailure)->{RecordIndex prepared=saved==null?null:buildIndex(saved);runOnUiThread(()->{
                                if(ticket!=generation||!unlocked()){next.close();return;}
                                setBusy(false);inputsEnabled(true);
                                if(commitFailure!=null){next.close();d.setCancelable(true);d.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);fail(commitFailure);return;}
                                data=saved;recordIndex=prepared;session=next;writerSession=next;original.close();d.dismiss();toast("密码已更换，请重新导出加密备份");consumePending();
                            });});
                        });
                    });
                });
            });
        });
    }
    private String handoffLabel(int action){
        if(action==EXPORT)return "重新解锁后继续导出加密备份。";
        if(action==VERIFY)return "重新解锁当前资料库后，再验证所选备份。";
        if(action==RESTORE)return store.exists()?"先解锁当前资料库，再输入备份导出时的密码。":"输入备份导出时的密码，核对后恢复。";
        if(action==IMPORT)return "重新解锁后核对旧 JSON，再导入本机。";
        if(action==DIAGNOSTICS)return "重新解锁后继续保存本地诊断报告。";
        return "请解锁继续使用。";
    }
    private void backups()throws Exception{
        if(maintenance!=null){backupMaintenance();return;}
        button(page,"返回设置",()->{tab=5;showApp();});
        text(page,"加密备份",22);text(page,Records.summary(data),14);text(page,capacityLabel(),13);
        text(page,"选择本机目录或离线 U 盘。导出后，请验证文件确实可解密。",14);
        if(backupOutcome!=null)text(page,backupOutcome,16);
        if(lastExportUri!=null)selected(button(page,"验证这份备份",()->readBackup(lastExportUri,true)),true);
        button(page,"导出加密备份",()->pick(EXPORT));
        button(page,"验证一份加密备份（不覆盖）",()->pick(VERIFY));
        button(page,"从加密备份恢复",()->confirm("恢复将替换当前资料库","请先导出并验证当前备份。选择文件后，需要输入备份导出时的密码；核对数量后才能替换。",()->pick(RESTORE)));
        button(page,"导入旧网页 JSON",()->{
            try{if(!Records.isEmpty(data)){saved("旧 JSON 只能导入空资料库，避免重复和覆盖。");return;}}
            catch(Exception e){fail(e);return;}
            confirm("导入旧网页数据","旧 JSON 本身是明文。导入只在本机完成；验证加密备份可恢复后，再处理旧明文副本。",()->pick(IMPORT));
        });
        text(page,"备份包含练习设置、记录、感悟、草稿及可恢复的已删除内容。旧梦境保留但不显示。",13);
        text(page,"维护",18);
        button(page,"已删除",()->{maintenance="deleted";scrollOffsets[8]=0;showApp();});
        button(page,"操作历史",()->{maintenance="audit";scrollOffsets[8]=0;showApp();});
        button(page,"版本与更新",()->{maintenance="version";scrollOffsets[8]=0;showApp();});
        button(page,"查看本地诊断",this::showDiagnostics);
    }
    private String entityLabel(String entity){
        switch(entity){case "practiceType":return "练习类型";case "durationPreset":return "常用时长";case "checkIn":return "练习记录";case "journal":return "感悟";default:return "归档资料";}
    }
    private String actionLabel(String action){
        switch(action){case "create":return "新增";case "update":return "修改";case "delete":return "移到已删除";case "restore":return "恢复";case "reorder":return "调整顺序";default:return "变更";}
    }
    private String deletedLabel(String entity,JSONObject r){
        switch(entity){case "practiceType":return r.optString("name");case "durationPreset":return r.optInt("minutes")+" 分钟";case "checkIn":return r.optString("practiceDate")+" · "+r.optString("practiceTypeName")+" · "+r.optInt("durationMinutes")+" 分钟";case "journal":return r.optString("journalDate")+" 感悟";default:return "归档资料";}
    }
    private void backupMaintenance()throws Exception{
        button(page,"返回备份",()->{maintenance=null;showApp();});
        if(maintenance.equals("deleted")){
            text(page,"已删除",22);text(page,"这些内容仍保存在加密资料库和完整备份中，可以恢复。",13);
            List<JSONObject> deleted=index().deletedRows;deletedPage=Math.min(deletedPage,Math.max(0,(deleted.size()-1)/PAGE_SIZE));
            for(JSONObject t:deleted.subList(deletedPage*PAGE_SIZE,Math.min((deletedPage+1)*PAGE_SIZE,deleted.size()))){
                JSONObject r=t.getJSONObject("record");String entity=t.getString("entity"),id=r.getString("id");LinearLayout card=recordCard();
                text(card,entityLabel(entity)+" · "+deletedLabel(entity,r),16);
                button(card,"恢复这条",()->{try{if("journal".equals(entity)&&t.has("archivedDraft")&&writer().journalDraftDays().contains(r.optString("journalDate"))){AlertDialog conflict=new AlertDialog.Builder(dialogContext()).setTitle("这一天已有未提交草稿").setMessage("请先保存或放弃当前草稿，再回来恢复；两份草稿都保留。保存会创建正式感悟，需先处理同日期记录才能恢复。").setNegativeButton("返回",null).setPositiveButton("打开当前草稿",(x,w)->{maintenance=null;tab=2;openJournal(r.optString("journalDate"));}).create();dialog(conflict);return;}}catch(Exception e){fail(e);return;}changeAsync(n->{JSONArray a=n.getJSONArray("deletedRecords");for(int i=0;i<a.length();i++){JSONObject gone=a.getJSONObject(i);if(entity.equals(gone.optString("entity"))&&id.equals(gone.getJSONObject("record").optString("id"))){Records.restore(n,i);return;}}throw new IllegalArgumentException();},()->{saved("已恢复，请核对记录或选项。");showApp();});});
            }
            if(deleted.isEmpty())text(page,"没有已删除内容。",14);
            if(deleted.size()>PAGE_SIZE)pageControls(deleted.size(),deletedPage,n->{deletedPage=n;showApp();});
        }else if(maintenance.equals("audit")){
            text(page,"操作历史",22);text(page,"本机加密历史 · 最近 50 条",13);JSONArray log=data.getJSONArray("auditLog");
            for(int i=log.length()-1,shown=0;i>=0&&shown<50;i--){JSONObject a=log.getJSONObject(i);if("dream".equals(a.optString("entity")))continue;shown++;text(page,RecordTime.label(new JSONObject().put("createdAt",a.opt("occurredAt")))+" · "+actionLabel(a.optString("action"))+" · "+entityLabel(a.optString("entity")),14);}
        }else{
            text(page,"版本与更新",22);text(page,"日课 "+BuildConfig.VERSION_NAME+" · "+BuildConfig.VERSION_CODE,14);
            text(page,BuildConfig.DEBUG?"体验测试版，仅使用虚构资料。":"Release 构建：正式使用前仍需稳定签名与真机验收。",14);
            text(page,"更新前导出并验证备份。使用相同包名、兼容签名和更高版本覆盖安装；不要先卸载或清除数据。\n签名不匹配时请停止。测试版与正式版不能互相覆盖。\n应用不联网检查更新，不会自动下载代码。",14);
        }
    }
    private void pick(int action){
        // No plaintext crosses into the external picker. onPause destroys our session.
        Intent intent=new Intent(action==EXPORT||action==DIAGNOSTICS?Intent.ACTION_CREATE_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("application/octet-stream");
        if(action!=EXPORT)intent.setType(action==DIAGNOSTICS?"text/plain":"*/*");
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY,true);
        if(action==EXPORT)intent.putExtra(Intent.EXTRA_TITLE,"rike-"+todayDate()+".rike");
        if(action==DIAGNOSTICS)intent.putExtra(Intent.EXTRA_TITLE,"rike-diagnostics.txt");
        pickerAction=action;
        try{startActivityForResult(intent,action);}catch(Exception e){pickerAction=0;diagnosticExport=null;fail(e);}
    }
    @Override protected void onActivityResult(int request,int result,Intent value){
        super.onActivityResult(request,result,value);
        if(request!=IMPORT&&request!=RESTORE&&request!=EXPORT&&request!=VERIFY&&request!=DIAGNOSTICS)return;
        pickerAction=0;
        if(result==RESULT_OK&&value!=null&&value.getData()!=null){pendingAction=request;pendingUri=value.getData();}
        else{pendingUri=null;pendingAction=0;if(request==DIAGNOSTICS)diagnosticExport=null;}
        // Some file providers deliver a result without the expected pause callback.
        // Always destroy the session before displaying the lock screen.
        lock();
    }
    private byte[] readUri(Uri uri)throws IOException{
        try(InputStream in=getContentResolver().openInputStream(uri)){return VaultStore.boundedRead(in);}
    }
    private <T> void background(Callable<T> operation,java.util.function.Consumer<T> success,Diagnostics.Code code){
        long token=generation;setBusy(true);inputsEnabled(false);status("正在本机处理…");
        crypto.execute(()->{
            T value=null;Exception failure=null;
            try{value=operation.call();}catch(Exception e){failure=e;diagnostics.record(code);}
            T result=value;Exception error=failure;
            runOnUiThread(()->{
                if(token!=generation||!unlocked())return;setBusy(false);inputsEnabled(true);
                if(error!=null){fail(error);status("操作未完成，原资料保留");return;}
                status("");success.accept(result);
            });
        });
    }
    private void consumePending(){
        if(!unlocked()||pendingUri==null)return;
        Uri uri=pendingUri;int action=pendingAction;pendingUri=null;pendingAction=0;
        if(action==DIAGNOSTICS){
            String report=diagnosticExport;diagnosticExport=null;
            if(report==null){toast("诊断已清空，请重新查看后导出。");return;}
            background(()->{
                try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){
                    if(out==null)throw new IOException();out.write(report.getBytes(StandardCharsets.UTF_8));out.flush();
                }return true;
            },ok->toast("诊断已保存到所选文件，未自动上传。"),Diagnostics.Code.DIAGNOSTIC_EXPORT_FAILED);return;
        }
        if(action==EXPORT){
            background(()->{
                byte[] ciphertext=store.read();try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){
                    if(out==null)throw new IOException();out.write(ciphertext);out.flush();
                }return true;
            },ok->{lastExportUri=uri;backupOutcome="备份已导出，尚未验证。请用备份密码验证这份文件。";saved(backupOutcome);showApp();},Diagnostics.Code.BACKUP_WRITE_FAILED);return;
        }
        if(action==IMPORT){
            try{if(!Records.isEmpty(data)||writer().dirty())throw new IllegalArgumentException();}catch(Exception e){fail(e);return;}
            long token=generation;
            background(()->{
                byte[] plain=readUri(uri);
                try{return Records.validate(new JSONObject(new String(plain,StandardCharsets.UTF_8)));}finally{Arrays.fill(plain,(byte)0);}
            },imported->{
                try{confirm("核对导入内容",Records.summary(imported)+"\n确认后保存为本机密文。",()->{
                    if(token!=generation||!unlocked())return;
                    try{if(!Records.isEmpty(data)||writer().dirty())throw new IllegalArgumentException();replaceAsync(imported,()->{toast("已导入，请核对记录并导出加密备份");showApp();});}catch(Exception e){fail(e);}
                });}catch(Exception e){fail(e);}
            },Diagnostics.Code.BACKUP_READ_FAILED);return;
        }
        readBackup(uri,action==VERIFY);
    }
    private void readBackup(Uri uri,boolean verifyOnly){
        LinearLayout form=column();form.setPadding(dp(18),dp(10),dp(18),dp(10));
        text(form,"输入这份文件导出时的主密码。它可能与当前手机的主密码不同；指纹不能替代备份解密。",14);
        EditText pass=field(form,"此备份的主密码","",false,true);Button keyboard=passwordKeyboard(form,pass);CheckBox recovery=new CheckBox(this);recovery.setText("使用恢复密钥");
        if(!BuildConfig.DEBUG){form.addView(recovery);recovery.setOnCheckedChangeListener((b,checked)->{pass.setRawInputType(passwordInputType(checked));keyboard.setVisibility(checked?View.GONE:View.VISIBLE);});}
        AlertDialog d=new AlertDialog.Builder(dialogContext()).setTitle(verifyOnly?"验证备份":"读取备份").setView(form).setNegativeButton("取消",null).setPositiveButton(verifyOnly?"验证备份":"读取备份",null).create();dialog(d);
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(busy)return;
            String password=pass.getText().toString();boolean useRecovery=!BuildConfig.DEBUG&&recovery.isChecked();pass.setText("");activeAction=d.getButton(AlertDialog.BUTTON_POSITIVE);setBusy(true);activeAction=null;d.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);long ticket=generation;
            crypto.execute(()->{
                VaultCrypto.Opened opened=null;JSONObject parsed=null;Exception error=null;char[] chars=password.toCharArray();
                try{
                    byte[] bytes=readUri(uri);opened=useRecovery?VaultCrypto.recover(bytes,password):VaultCrypto.open(bytes,chars);
                    try{parsed=Records.validate(new JSONObject(new String(opened.plaintext,StandardCharsets.UTF_8)));}
                    finally{Arrays.fill(opened.plaintext,(byte)0);}
                }catch(Exception e){error=e;}finally{Arrays.fill(chars,'\0');if(opened!=null)opened.session.close();}
                JSONObject result=parsed;Exception problem=error;
                runOnUiThread(()->{
                    if(ticket!=generation||!unlocked())return;setBusy(false);
                    if(!d.isShowing())return;
                    if(problem!=null){diagnostics.record(verifyOnly?Diagnostics.Code.BACKUP_VERIFY_FAILED:Diagnostics.Code.BACKUP_READ_FAILED);fail(problem);return;}d.dismiss();
                    try{
                        if(verifyOnly){backupOutcome=(uri.equals(lastExportUri)?"刚导出的备份":"所选备份")+"已成功解密并校验。当前资料库没有被修改。";saved(backupOutcome);showApp();info("备份验证成功",Records.summary(result)+"\n"+backupOutcome);return;}
                        confirm("确认替换当前资料库？","备份中的内容：\n"+Records.summary(result)+"\n\n当前手机的内容：\n"+Records.summary(data)+"\n\n替换包含记录、设置、草稿及已删除内容；恢复数据将使用当前主密码重新加密。",()->{
                            if(ticket!=generation||!unlocked())return;
                            replaceAsync(result,()->{saved("备份已恢复，使用当前主密码保存在本机。");showApp();},()->diagnostics.record(Diagnostics.Code.RESTORE_WRITE_FAILED));
                        });
                    }catch(Exception e){fail(e);}
                });
            });
        });
    }
}
