package app.rike.offline;
import org.junit.Test;
import org.json.*;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;

public class RecordsTest {
    @Test public void journalUniquenessIsEnforcedBeforeMutation()throws Exception{
        JSONObject d=Records.empty();
        JSONObject first=new JSONObject().put("id","j1").put("journalDate","2026-09-17").put("content","first");
        Records.upsert(d,"journal",first);first.put("content","edited");Records.upsert(d,"journal",first);
        assertEquals(1,d.getJSONArray("journals").length());
        String before=d.toString();
        try{Records.upsert(d,"journal",Records.copy(first).put("id","j2"));fail("duplicate day accepted");}
        catch(IllegalArgumentException expected){}
        assertEquals(before,d.toString());
        Records.validate(d);
    }
    @Test public void deletionArchivesDraftAndRestoreProtectsNewDraft()throws Exception{
        JSONObject d=Records.empty();Records.upsert(d,"dream",dream("a"));
        d.getJSONObject("drafts").put("dream",new JSONObject().put("id","a").put("content","unfinished edit"));
        Records.delete(d,"dream","a");
        assertFalse(d.getJSONObject("drafts").has("dream"));
        assertEquals("unfinished edit",d.getJSONArray("deletedRecords").getJSONObject(0).getJSONObject("archivedDraft").getString("content"));
        d.getJSONObject("drafts").put("dream",new JSONObject().put("content","another dream"));
        String before=d.toString();
        try{Records.restore(d,0);fail("draft overwritten");}catch(IllegalArgumentException expected){}
        assertEquals(before,d.toString());
        d.getJSONObject("drafts").remove("dream");Records.restore(d,0);
        assertEquals("unfinished edit",d.getJSONObject("drafts").getJSONObject("dream").getString("content"));
        JSONObject j=new JSONObject().put("id","j").put("journalDate","2026-09-17").put("content","journal");
        Records.upsert(d,"journal",j);d.getJSONObject("drafts").put("journal:2026-09-17",new JSONObject().put("content","journal edit"));
        Records.delete(d,"journal","j");assertFalse(d.getJSONObject("drafts").has("journal:2026-09-17"));
        Records.restore(d,0);assertEquals("journal edit",d.getJSONObject("drafts").getJSONObject("journal:2026-09-17").getString("content"));
    }
    private static JSONObject dream(String id)throws Exception{return new JSONObject().put("id",id).put("sleepDate","2026-09-16").put("sleepPeriod","nap").put("content","synthetic dream");}
    @Test public void multipleDreamsAndDeletionHistorySurviveEncryptedMigration()throws Exception{
        JSONObject d=Records.empty();Records.upsert(d,"dream",dream("a"));Records.upsert(d,"dream",dream("b"));
        Records.delete(d,"dream","a");d.getJSONObject("drafts").put("dream",new JSONObject().put("content","unsaved synthetic note"));
        JSONObject migrated=Records.validate(d);
        VaultCrypto.Created c=VaultCrypto.create("synthetic-test-password".toCharArray());
        byte[] file=VaultCrypto.encrypt(c.session,migrated.toString().getBytes(StandardCharsets.UTF_8));
        VaultCrypto.Opened o=VaultCrypto.recover(file,c.recoveryCode);
        JSONObject restored=Records.validate(new JSONObject(new String(o.plaintext,StandardCharsets.UTF_8)));
        assertEquals(1,restored.getJSONArray("dreams").length());assertEquals(1,restored.getJSONArray("deletedRecords").length());
        assertEquals(3,restored.getJSONArray("auditLog").length());assertEquals("unsaved synthetic note",restored.getJSONObject("drafts").getJSONObject("dream").getString("content"));
        Records.restore(restored,0);assertEquals(2,restored.getJSONArray("dreams").length());assertEquals(4,restored.getJSONArray("auditLog").length());
        c.session.close();o.session.close();
    }
    @Test public void removingAndRenamingTypePreservesHistoricName()throws Exception{
        JSONObject d=Records.empty(),type=new JSONObject().put("id","t").put("name","original");
        Records.upsert(d,"practiceType",type);
        Records.upsert(d,"checkIn",new JSONObject().put("id","c").put("practiceTypeId","t").put("practiceTypeName","original").put("durationMinutes",30).put("practiceDate","2026-09-16"));
        type.put("name","renamed");Records.upsert(d,"practiceType",type);Records.delete(d,"practiceType","t");
        assertEquals("original",d.getJSONArray("checkIns").getJSONObject(0).getString("practiceTypeName"));
    }
    @Test public void malformedImportDoesNotMutateOriginalAndLegacySupported()throws Exception{
        JSONObject d=Records.empty();d.put("schemaVersion",1);d.remove("dreams");
        assertEquals(0,Records.validate(d).getJSONArray("dreams").length());assertFalse(d.has("dreams"));
        JSONObject invalid=Records.empty();invalid.getJSONArray("dreams").put(dream("same")).put(dream("same"));
        try{Records.validate(invalid);fail();}catch(IllegalArgumentException expected){}
        assertEquals(2,invalid.getJSONArray("dreams").length());
        try{Records.date("2026-02-30");fail();}catch(IllegalArgumentException expected){}
    }
}
