package app.rike.offline;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Data-format compatibility; not a substitute for real-device APK upgrade tests. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class UpgradeCompatibilityTest {
    private static final char[] PASSWORD="synthetic-upgrade-password".toCharArray();
    @Test public void existingV1VaultKeepsAllRecordsKeysDraftsAndMetadata()throws Exception {
        // Literal old offlineVersion=1 contract. Do not replace with Records.empty().
        JSONObject old=new JSONObject("{\"format\":\"rke-data-export\",\"schemaVersion\":3,\"offlineVersion\":1,\"deviceId\":\"synthetic-device\",\"revision\":7,\"practiceTypes\":[{\"id\":\"t\",\"name\":\"练习\"}],\"durationPresets\":[{\"id\":\"m\",\"minutes\":30}],\"checkIns\":[{\"id\":\"c\",\"practiceDate\":\"2026-09-01\",\"practiceTypeName\":\"原名称\",\"durationMinutes\":30}],\"journals\":[{\"id\":\"j\",\"journalDate\":\"2026-09-01\",\"content\":\"虚构日记\"}],\"dreams\":[{\"id\":\"d\",\"sleepDate\":\"2026-09-01\",\"sleepPeriod\":\"night\",\"content\":\"虚构梦境\"}],\"deletedRecords\":[{\"entity\":\"journal\",\"record\":{\"id\":\"gone\",\"journalDate\":\"2026-08-01\",\"content\":\"虚构删除记录\"},\"archivedDraft\":{\"content\":\"删除前草稿\"}}],\"auditLog\":[{\"id\":\"audit\",\"snapshot\":{\"content\":\"虚构历史\"}}],\"drafts\":{\"dream\":{\"content\":\"未保存梦境\"},\"journal:2026-09-02\":{\"content\":\"未保存日记\"}},\"extraMetadata\":{\"retain\":true}}");
        VaultCrypto.Created keys=VaultCrypto.create(PASSWORD);VaultStore store=new VaultStore(RuntimeEnvironment.getApplication());
        store.write(VaultCrypto.encrypt(keys.session,old.toString().getBytes(StandardCharsets.UTF_8)));byte[] before=store.read();keys.session.close();
        VaultCrypto.Opened opened=VaultCrypto.open(store.read(),PASSWORD);
        JSONObject read=Records.validate(new JSONObject(new String(opened.plaintext,StandardCharsets.UTF_8)));Arrays.fill(opened.plaintext,(byte)0);
        assertEquals(old.toString(),read.toString());assertArrayEquals(before,store.read());
        assertEquals(read.toString(),Records.validate(read).toString());
        store.write(VaultCrypto.encrypt(opened.session,read.toString().getBytes(StandardCharsets.UTF_8)));opened.session.close();
        VaultCrypto.Opened restored=VaultCrypto.recover(store.read(),keys.recoveryCode);
        assertEquals(old.toString(),new String(restored.plaintext,StandardCharsets.UTF_8));Arrays.fill(restored.plaintext,(byte)0);restored.session.close();
    }
    @Test public void legacyWebV1AndV2AreAdditiveAndDoNotMutateSource()throws Exception {
        for(int version:new int[]{1,2}){
            JSONObject old=new JSONObject("{\"format\":\"rke-data-export\",\"schemaVersion\":"+version+",\"practiceTypes\":[],\"checkIns\":[],\"journals\":[],\"custom\":{\"keep\":true}}");
            String before=old.toString();JSONObject migrated=Records.validate(old);
            assertEquals(before,old.toString());assertEquals(3,migrated.getInt("schemaVersion"));
            assertEquals(0,migrated.getJSONArray("dreams").length());assertTrue(migrated.getJSONObject("custom").getBoolean("keep"));
            assertEquals(migrated.toString(),Records.validate(migrated).toString());
        }
    }
    @Test public void unsupportedOrCoercedVersionsCannotOverwriteExistingVault()throws Exception {
        VaultStore store=new VaultStore(RuntimeEnvironment.getApplication());VaultCrypto.Created keys=VaultCrypto.create(PASSWORD);
        store.write(VaultCrypto.encrypt(keys.session,Records.empty().toString().getBytes(StandardCharsets.UTF_8)));byte[] before=store.read();
        for(String key:new String[]{"offlineVersion","schemaVersion"})for(Object invalid:new Object[]{4,0,-1,1.5,"1",JSONObject.NULL,4294967297L}){
            JSONObject incoming=Records.empty().put(key,invalid);String unchanged=incoming.toString();
            try{JSONObject validated=Records.validate(incoming);store.write(VaultCrypto.encrypt(keys.session,validated.toString().getBytes(StandardCharsets.UTF_8)));fail("Must reject unsupported version");}
            catch(IllegalArgumentException expected){assertEquals(unchanged,incoming.toString());}
            assertArrayEquals(before,store.read());
        }
        keys.session.close();
    }
}
