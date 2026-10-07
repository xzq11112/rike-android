package app.rike.offline;

import android.app.AlertDialog;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

/** Legacy nullable remarks must not become the visible word "null". */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class CheckInNoteTest {
    private static void set(MainActivity a,String name,Object value)throws Exception{
        Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(a,value);
    }
    private static void open(MainActivity a,JSONObject data)throws Exception{
        VaultCrypto.Created key=VaultCrypto.create("synthetic-note-password".toCharArray());
        new VaultStore(a).write(VaultCrypto.encrypt(key.session,data.toString().getBytes(StandardCharsets.UTF_8)));
        set(a,"session",key.session);set(a,"data",data);
        Method m=MainActivity.class.getDeclaredMethod("showApp");m.setAccessible(true);m.invoke(a);
    }
    private static JSONObject check(String id)throws Exception{
        return new JSONObject().put("id",id).put("practiceDate",LocalDate.now().toString())
            .put("practiceTypeName","SYNTHETIC-TYPE").put("durationMinutes",20);
    }
    private static long countText(View root,String value){
        return TestWork.views(root,TextView.class).stream().filter(v->value.contentEquals(v.getText())).count();
    }
    @Test public void nullableImportedRemarksAreHiddenWhileActualTextIsPreserved()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();
        try{
            JSONObject data=Records.empty();
            data.getJSONArray("checkIns").put(check("missing")).put(check("json-null").put("note",JSONObject.NULL))
                .put(check("empty").put("note","")).put(check("text").put("note","SYNTHETIC-NOTE"));
            open(a,Records.validate(data));TestWork.navigate(a,1);View screen=a.getWindow().getDecorView();
            assertEquals(0,countText(screen,"null"));assertEquals(1,countText(screen,"SYNTHETIC-NOTE"));
            assertEquals("",Records.checkInNote(check("absent")));assertEquals("",Records.checkInNote(check("null").put("note",JSONObject.NULL)));
            // A user who deliberately wrote this word still owns that remark.
            assertEquals("null",Records.checkInNote(check("literal").put("note","null")));
            assertEquals(" null ",Records.checkInNote(check("spaced").put("note"," null ")));
        }finally{c.pause().stop().destroy();}
    }
    @Test public void nullableRemarkStartsBlankInBothEditorAndTodayDraft()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();
        try{
            JSONObject data=Records.empty(),row=check("json-null").put("note",JSONObject.NULL);
            data.getJSONArray("checkIns").put(row);data.getJSONObject("drafts").put("checkIn",new JSONObject().put("note",JSONObject.NULL));
            open(a,Records.validate(data));List<EditText> fields=TestWork.views(a.getWindow().getDecorView(),EditText.class);
            assertEquals("",fields.get(1).getText().toString());assertEquals(View.GONE,((View)fields.get(1).getParent()).getVisibility());
            Method edit=MainActivity.class.getDeclaredMethod("editCheckIn",JSONObject.class);edit.setAccessible(true);edit.invoke(a,row);
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();fields=TestWork.views(dialog.getWindow().getDecorView(),EditText.class);
            assertEquals("",fields.get(1).getText().toString());
        }finally{c.pause().stop().destroy();}
    }
}
