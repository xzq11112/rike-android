package app.rike.offline;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Process-wide ownership and FIFO for one vault. No Activity owns this worker.
 * Acquiring on the main thread revokes the old UI synchronously, so its final
 * flush is queued before the new owner can enqueue any read or mutation. */
final class VaultAccess {
    interface Owner { void revokeVault(); }
    private static final Map<String,VaultAccess> VAULTS=new HashMap<>();
    static synchronized VaultAccess forPath(String path){return VAULTS.computeIfAbsent(path,p->new VaultAccess());}
    final Object diskLock=new Object();
    final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"rike-vault");t.setDaemon(true);return t;});
    private WeakReference<Owner> active=new WeakReference<>(null);
    synchronized void acquire(Owner next){Owner old=active.get();if(old==next)return;if(old!=null)old.revokeVault();active=new WeakReference<>(next);}
    synchronized boolean owns(Owner owner){return active.get()==owner;}
    synchronized void release(Owner owner){if(active.get()==owner)active.clear();}
}
