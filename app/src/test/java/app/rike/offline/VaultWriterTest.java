package app.rike.offline;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class VaultWriterTest {
    private static JSONObject read(byte[] bytes)throws Exception{VaultCrypto.Opened o=VaultCrypto.open(bytes,"012345".toCharArray());try{return new JSONObject(new String(o.plaintext,StandardCharsets.UTF_8));}finally{java.util.Arrays.fill(o.plaintext,(byte)0);o.session.close();}}
    @Test public void failedWriteRestoresDraftAndSerialEditsUseLastCommittedSnapshot()throws Exception{
        ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();VaultCrypto.Created keys=VaultCrypto.create("012345".toCharArray());JSONObject initial=Records.empty();
        AtomicReference<byte[]> disk=new AtomicReference<>(VaultCrypto.encrypt(keys.session,initial.toString().getBytes(StandardCharsets.UTF_8)));byte[] before=disk.get();AtomicBoolean fail=new AtomicBoolean(true);AtomicInteger errors=new AtomicInteger();
        VaultWriter writer=new VaultWriter(worker,keys.session,initial,(key,data)->{if(fail.get())throw new java.io.IOException();disk.set(VaultCrypto.encrypt(key,data.toString().getBytes(StandardCharsets.UTF_8)));},(data,error)->{if(error!=null)errors.incrementAndGet();});
        JSONObject input=new JSONObject().put("content","SYNTHETIC-DRAFT");writer.stage("journal:2026-01-01",input);input.put("content","UNCOMMITTED-CALLER-EDIT");writer.flushSoon();worker.submit(()->{}).get(10,TimeUnit.SECONDS);
        assertSame(before,disk.get());assertEquals(initial.toString(),writer.current().toString());assertEquals("SYNTHETIC-DRAFT",writer.draft("journal:2026-01-01").getString("content"));assertEquals(1,errors.get());
        fail.set(false);writer.edit(data->data.put("syntheticOne",true),(data,error)->assertNull(error));writer.edit(data->data.put("syntheticTwo",true),(data,error)->assertNull(error));worker.submit(()->{}).get(10,TimeUnit.SECONDS);
        JSONObject saved=read(disk.get());assertTrue(saved.getBoolean("syntheticOne"));assertTrue(saved.getBoolean("syntheticTwo"));assertEquals("SYNTHETIC-DRAFT",saved.getJSONObject("drafts").getJSONObject("journal:2026-01-01").getString("content"));
        writer.close();worker.submit(()->{}).get(10,TimeUnit.SECONDS);worker.shutdown();keys.session.close();
    }
    @Test public void replacingSnapshotDiscardsOldDraftsAndRekeyKeepsLatestData()throws Exception{
        ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();VaultCrypto.Created keys=VaultCrypto.create("012345".toCharArray());AtomicReference<byte[]> disk=new AtomicReference<>();
        VaultWriter writer=new VaultWriter(worker,keys.session,Records.empty(),(key,data)->disk.set(VaultCrypto.encrypt(key,data.toString().getBytes(StandardCharsets.UTF_8))),(data,error)->{});
        writer.stage("old",new JSONObject().put("content","OLD-DRAFT"));JSONObject replacement=Records.empty().put("syntheticReplaced",true);writer.replace(replacement,(data,error)->assertNull(error));worker.submit(()->{}).get(10,TimeUnit.SECONDS);
        assertFalse(writer.current().getJSONObject("drafts").has("old"));writer.stage("new",new JSONObject().put("content","NEW-DRAFT"));VaultCrypto.Session next=VaultCrypto.changePassword(keys.session,"654321".toCharArray());writer.rekey(next,(data,error)->assertNull(error));worker.submit(()->{}).get(10,TimeUnit.SECONDS);
        VaultCrypto.Opened o=VaultCrypto.open(disk.get(),"654321".toCharArray());JSONObject saved=new JSONObject(new String(o.plaintext,StandardCharsets.UTF_8));assertTrue(saved.getBoolean("syntheticReplaced"));assertEquals("NEW-DRAFT",saved.getJSONObject("drafts").getJSONObject("new").getString("content"));o.session.close();next.close();writer.close();worker.submit(()->{}).get(10,TimeUnit.SECONDS);worker.shutdown();keys.session.close();
    }
    @Test public void capacityFailureDuringDeleteAndRestorePreservesCiphertextAndEveryArchive()throws Exception{
        ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();VaultCrypto.Created keys=VaultCrypto.create("012345".toCharArray());
        try{
            for(boolean restoring:new boolean[]{false,true}){
                JSONObject initial=Records.empty();Records.upsert(initial,"journal",new JSONObject().put("id","j").put("journalDate","2026-01-01").put("content","synthetic archived body"));if(restoring)Records.delete(initial,"journal","j");
                initial.put("syntheticCapacity","");int bytes=initial.toString().getBytes(StandardCharsets.UTF_8).length;initial.put("syntheticCapacity","x".repeat(VaultCrypto.MAX_PLAINTEXT-bytes-1));
                AtomicReference<byte[]> disk=new AtomicReference<>(VaultCrypto.encrypt(keys.session,initial.toString().getBytes(StandardCharsets.UTF_8)));byte[] before=disk.get();AtomicReference<Exception> error=new AtomicReference<>();
                VaultWriter writer=new VaultWriter(worker,keys.session,initial,(key,next)->disk.set(VaultCrypto.encrypt(key,next.toString().getBytes(StandardCharsets.UTF_8))),(next,problem)->{});
                writer.edit(next->{if(restoring)Records.restore(next,0);else Records.delete(next,"journal","j");},(next,problem)->error.set(problem));worker.submit(()->{}).get(20,TimeUnit.SECONDS);
                assertTrue(error.get() instanceof VaultCapacityException);assertSame(before,disk.get());assertEquals(initial.toString(),writer.current().toString());assertEquals(initial.toString(),read(disk.get()).toString());writer.close();worker.submit(()->{}).get(20,TimeUnit.SECONDS);
            }
        }finally{worker.shutdown();keys.session.close();}
    }

}
