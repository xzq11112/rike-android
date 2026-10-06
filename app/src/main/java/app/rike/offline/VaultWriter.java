package app.rike.offline;

import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.*;

/** One serial owner of committed snapshots. UI stages small drafts only; every
 * full JSON copy, encryption and atomic disk write happens on the worker.
 * On lock the UI key closes immediately; a worker-only copy exists just until
 * queued writes and the final draft flush finish. It never unlocks an Activity.
 */
final class VaultWriter {
    interface Edit { void apply(JSONObject next)throws Exception; }
    interface Sink { void save(VaultCrypto.Session key,JSONObject next)throws Exception; }
    interface Done { void accept(JSONObject next,Exception error); }
    private final ScheduledExecutorService worker;
    private final Sink sink;
    private final Done notification;
    private VaultCrypto.Session key;
    private volatile JSONObject committed;
    private final Map<String,JSONObject> pending=new LinkedHashMap<>();
    private Map<String,JSONObject> flight=Collections.emptyMap();
    private ScheduledFuture<?> debounce,deadline;
    private boolean closing;
    VaultWriter(ScheduledExecutorService worker,VaultCrypto.Session key,JSONObject data,Sink sink,Done notification){
        this.worker=worker;this.key=VaultCrypto.duplicate(key);this.committed=data;this.sink=sink;this.notification=notification;
    }
    synchronized void stage(String name,JSONObject value)throws Exception{
        if(closing)throw new IllegalStateException();
        pending.put(name,Records.copy(value));
        if(debounce!=null)debounce.cancel(false);
        debounce=worker.schedule(this::flush,800,TimeUnit.MILLISECONDS);
        if(deadline==null)deadline=worker.schedule(this::flush,2500,TimeUnit.MILLISECONDS);
    }
    synchronized JSONObject draft(String name)throws Exception{
        JSONObject d=pending.get(name);if(d==null)d=flight.get(name);
        if(d==null)d=committed.getJSONObject("drafts").optJSONObject(name);
        return d==null?new JSONObject():Records.copy(d);
    }
    JSONObject current(){return committed;}
    synchronized boolean dirty(){return !pending.isEmpty()||!flight.isEmpty();}
    synchronized List<String> journalDraftDays()throws Exception{
        Set<String> names=new HashSet<>();committed.getJSONObject("drafts").keys().forEachRemaining(names::add);names.addAll(flight.keySet());names.addAll(pending.keySet());
        List<String> days=new ArrayList<>();for(String name:names)if(name.startsWith("journal:")){
            String day=name.substring(8);try{Records.date(day);days.add(day);}catch(IllegalArgumentException ignored){}
        }
        days.sort(Collections.reverseOrder());return days;
    }
    private synchronized void cancelTimers(){
        if(debounce!=null)debounce.cancel(false);if(deadline!=null)deadline.cancel(false);debounce=null;deadline=null;
    }
    private synchronized Map<String,JSONObject> take(){
        cancelTimers();Map<String,JSONObject> result=new LinkedHashMap<>(pending);pending.clear();flight=result;return result;
    }
    private synchronized void finish(Map<String,JSONObject> batch,boolean success){
        if(!success)for(Map.Entry<String,JSONObject> d:batch.entrySet())pending.putIfAbsent(d.getKey(),d.getValue());
        flight=Collections.emptyMap();
    }
    private void flush(){
        synchronized(this){if(pending.isEmpty()){cancelTimers();return;}}
        commit(null,null,null,null);
    }
    synchronized void edit(Edit edit,Done done){
        if(closing)throw new IllegalStateException();cancelTimers();worker.execute(()->commit(edit,null,null,done));
    }
    synchronized void replace(JSONObject data,Done done){
        if(closing)throw new IllegalStateException();cancelTimers();worker.execute(()->commit(null,data,null,done));
    }
    synchronized void rekey(VaultCrypto.Session replacement,Done done){
        if(closing)throw new IllegalStateException();cancelTimers();VaultCrypto.Session copy=VaultCrypto.duplicate(replacement);
        worker.execute(()->commit(null,null,copy,done));
    }
    private void commit(Edit edit,JSONObject replacement,VaultCrypto.Session newKey,Done done){
        Map<String,JSONObject> batch=take();Exception failure=null;JSONObject next=null;
        try{
            next=Records.copy(replacement==null?committed:replacement);
            if(replacement==null)for(Map.Entry<String,JSONObject> d:batch.entrySet())next.getJSONObject("drafts").put(d.getKey(),d.getValue());
            if(edit!=null)edit.apply(next);
            sink.save(newKey==null?key:newKey,next);
            committed=next;
            if(newKey!=null){key.close();key=newKey;}
        }catch(Exception e){failure=e;if(newKey!=null)newKey.close();}
        finish(batch,failure==null);
        if(done==null)notification.accept(failure==null?next:null,failure);else done.accept(failure==null?next:null,failure);
    }
    synchronized void flushSoon(){if(!closing){cancelTimers();worker.execute(this::flush);}}
    synchronized void close(){
        if(closing)return;closing=true;cancelTimers();
        worker.execute(()->{
            try{flush();}finally{
                key.close();synchronized(VaultWriter.this){pending.clear();flight=Collections.emptyMap();committed=null;}
            }
        });
    }
}
