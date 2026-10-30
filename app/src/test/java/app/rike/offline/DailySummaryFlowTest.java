package app.rike.offline;

import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import java.lang.reflect.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class DailySummaryFlowTest {
    private static void set(MainActivity a,String key,Object value)throws Exception {
        Field f=MainActivity.class.getDeclaredField(key);f.setAccessible(true);f.set(a,value);
    }
    @Test public void historicalDateExportsOnlyItsDisplayedCredits()throws Exception {
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a=TestWork.offline(controller.get());
        try {
            JSONObject data=Records.empty();
            data.getJSONArray("checkIns").put(DailySummaryTest.record("阴性写作",31,0.7).put("id","a"))
                .put(DailySummaryTest.record("阴性写作",31,0.7).put("id","b"))
                .put(DailySummaryTest.record("静坐",60,1).put("id","c"))
                .put(DailySummaryTest.record("别日",100,1).put("id","d").put("practiceDate","2026-10-28"));
            VaultCrypto.Created keys=VaultCrypto.create("synthetic-summary-password".toCharArray());
            set(a,"session",keys.session);set(a,"data",data);set(a,"tab",1);set(a,"historyDate","2026-10-29");
            Method show=MainActivity.class.getDeclaredMethod("showApp");show.setAccessible(true);show.invoke(a);TestWork.layout(a);
            View root=a.getWindow().getDecorView();
            assertNotNull(TestWork.named(root,"小计 1小时42分钟"));
            View export=TestWork.named(root,"导出 2026-10-29 的练习记录");assertNotNull(export);export.performClick();
            ClipboardManager clipboard=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);
            assertEquals("阴性写作 42分钟\n静坐 1小时",clipboard.getPrimaryClip().getItemAt(0).getText().toString());
            controller.pause();
            assertNotNull(clipboard.getPrimaryClip());
        } finally {controller.stop().destroy();}
    }
}
