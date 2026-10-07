package app.rike.offline;

import org.json.*;
import java.time.*;
import java.util.*;

/** Pure model. Copies are validated before an atomic encrypted commit.
 * Check-ins retain their original type name even when that type changes.
 */
public final class Records {
    public static final int SCHEMA_VERSION = 3, OFFLINE_VERSION = 1;
    public static final String[] ENTITIES = {"practiceType", "durationPreset", "checkIn", "journal", "dream"};
    public static final String[] ARRAYS = {"practiceTypes", "durationPresets", "checkIns", "journals", "dreams"};
    public static String id() { return UUID.randomUUID().toString(); }
    public static String now() { return Instant.now().toString(); }
    public static JSONObject copy(JSONObject object) throws JSONException { return new JSONObject(object.toString()); }
    /** Optional remarks from old web exports may contain JSON null, not text. */
    public static String checkInNote(JSONObject record) {
        Object value = record.opt("note");
        return value instanceof String ? (String)value : "";
    }
    public static String array(String entity) {
        for (int i = 0; i < ENTITIES.length; i++) if (ENTITIES[i].equals(entity)) return ARRAYS[i];
        throw new IllegalArgumentException("记录类型不支持");
    }
    public static JSONObject empty() throws JSONException {
        JSONObject d = new JSONObject().put("format", "rke-data-export").put("schemaVersion", SCHEMA_VERSION)
            .put("offlineVersion", OFFLINE_VERSION).put("deviceId", id()).put("revision", 0).put("drafts", new JSONObject());
        for (String a : ARRAYS) d.put(a, new JSONArray());
        return d.put("deletedRecords", new JSONArray()).put("auditLog", new JSONArray());
    }
    public static JSONObject find(JSONObject d, String entity, String id) throws JSONException {
        JSONArray a = d.getJSONArray(array(entity));
        for (int i = 0; i < a.length(); i++) if (id.equals(a.getJSONObject(i).getString("id"))) return a.getJSONObject(i);
        return null;
    }
    public static JSONObject journal(JSONObject d, String date) throws JSONException {
        JSONArray a = d.getJSONArray("journals");
        for (int i = 0; i < a.length(); i++) if (date.equals(a.getJSONObject(i).getString("journalDate"))) return a.getJSONObject(i);
        return null;
    }
    public static void date(String value) {
        try { if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || !LocalDate.parse(value).toString().equals(value)) throw new Exception(); }
        catch (Exception e) { throw new IllegalArgumentException("日期请使用 YYYY-MM-DD 格式"); }
    }
    public static int minutes(String value) {
        try { int n = Integer.parseInt(value); if (n > 0 && n <= 1440) return n; } catch (Exception ignored) { }
        throw new IllegalArgumentException("时长需为 1–1440 的整数分钟");
    }
    private static void string(JSONObject r, String key, int max, boolean required) throws JSONException {
        if (r.isNull(key) && !required) return;
        Object value = r.get(key);
        if (!(value instanceof String) || ((String)value).length() > max || (required && ((String)value).trim().isEmpty()))
            throw new IllegalArgumentException("记录字段无效：" + key);
    }
    public static void validateRecord(String entity, JSONObject r) throws JSONException {
        string(r, "id", 200, true);
        switch (entity) {
            case "practiceType": string(r, "name", 60, true); break;
            case "durationPreset": minutes(r.get("minutes").toString()); string(r, "label", 60, false); break;
            case "checkIn":
                date(r.getString("practiceDate")); string(r, "practiceTypeName", 60, true);
                minutes(r.get("durationMinutes").toString()); string(r, "note", 50000, false); break;
            case "journal": date(r.getString("journalDate")); string(r, "content", 50000, true); break;
            case "dream":
                date(r.getString("sleepDate")); string(r, "content", 50000, true); string(r, "associations", 50000, false);
                if (!Arrays.asList("night", "nap", "other").contains(r.getString("sleepPeriod"))) throw new IllegalArgumentException("睡眠类别不支持");
                break;
            default: throw new IllegalArgumentException("记录类型不支持");
        }
    }
    /** v1/v2/v3 imports retain unknown metadata but reject malformed or duplicate live rows. */
    public static JSONObject validate(JSONObject source) throws JSONException {
        JSONObject d = copy(source);
        // JSONObject.getInt coerces strings and truncates fractions. Never silently
        // treat a future or malformed version as a supported format and rewrite it.
        int schema = version(d, "schemaVersion");
        if (!"rke-data-export".equals(d.getString("format")) || schema < 1 || schema > SCHEMA_VERSION)
            throw new IllegalArgumentException("不是支持的日课备份");
        if (d.has("offlineVersion") && version(d, "offlineVersion") != OFFLINE_VERSION) throw new IllegalArgumentException("请使用更新版本打开此资料库");
        if (!d.has("dreams") && schema < 3) d.put("dreams", new JSONArray());
        if (!d.has("durationPresets")) d.put("durationPresets", new JSONArray());
        if (!d.has("deletedRecords")) d.put("deletedRecords", new JSONArray());
        if (!d.has("auditLog")) d.put("auditLog", new JSONArray());
        for (int e = 0; e < ENTITIES.length; e++) {
            JSONArray a = d.getJSONArray(ARRAYS[e]); Set<String> ids = new HashSet<>(), dates = new HashSet<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject r = a.getJSONObject(i); validateRecord(ENTITIES[e], r);
                if (!ids.add(r.getString("id"))) throw new IllegalArgumentException("备份包含重复记录 ID");
                if (e == 3 && !dates.add(r.getString("journalDate"))) throw new IllegalArgumentException("同一天包含多篇日记");
            }
        }
        JSONArray deleted = d.getJSONArray("deletedRecords");
        for (int i = 0; i < deleted.length(); i++) {
            JSONObject row = deleted.getJSONObject(i);
            validateRecord(row.getString("entity"), row.getJSONObject("record"));
        }
        JSONArray audit = d.getJSONArray("auditLog");
        for (int i = 0; i < audit.length(); i++) audit.getJSONObject(i);
        if (!d.has("drafts")) d.put("drafts", new JSONObject()); d.getJSONObject("drafts");
        if (!d.has("deviceId")) d.put("deviceId", id());
        if (!d.has("revision")) d.put("revision", 0);
        return d.put("offlineVersion", OFFLINE_VERSION).put("schemaVersion", SCHEMA_VERSION);
    }
    private static int version(JSONObject d, String key) throws JSONException {
        Object value = d.get(key);
        if (!(value instanceof Integer || value instanceof Long)) throw new IllegalArgumentException("资料库版本格式无效");
        long n = ((Number)value).longValue();
        if (n < 1 || n > Integer.MAX_VALUE) throw new IllegalArgumentException("资料库版本格式无效");
        return (int)n;
    }
    private static void log(JSONObject d, String entity, String action, JSONObject r) throws JSONException {
        long rev = d.optLong("revision") + 1; d.put("revision", rev);
        d.getJSONArray("auditLog").put(new JSONObject().put("id", id()).put("deviceId", d.getString("deviceId"))
            .put("sequence", rev).put("entity", entity).put("entityId", r.getString("id")).put("action", action)
            .put("occurredAt", now()).put("snapshot", copy(r)));
    }
    public static List<JSONObject> options(JSONObject d,String entity)throws JSONException{
        JSONArray a=d.getJSONArray(array(entity));List<JSONObject> rows=new ArrayList<>();for(int i=0;i<a.length();i++)rows.add(a.getJSONObject(i));
        if("durationPreset".equals(entity))rows.sort(Comparator.comparingInt(r->r.optInt("minutes")));
        else if("practiceType".equals(entity)){
            Map<JSONObject,Integer> positions=new IdentityHashMap<>();for(int i=0;i<rows.size();i++)positions.put(rows.get(i),i);
            rows.sort(Comparator.comparingInt(r->r.optInt("sortOrder",positions.get(r))));
        }
        return rows;
    }
    private static void sortOptions(JSONObject d,String entity)throws JSONException{
        List<JSONObject> rows=options(d,entity);JSONArray sorted=new JSONArray();for(int i=0;i<rows.size();i++){JSONObject row=rows.get(i);row.put("sortOrder",i);sorted.put(row);}d.put(array(entity),sorted);
    }
    public static void movePracticeType(JSONObject d,String id,int delta)throws JSONException{
        List<JSONObject> rows=options(d,"practiceType");int from=-1;for(int i=0;i<rows.size();i++)if(id.equals(rows.get(i).getString("id")))from=i;
        if(from<0||Math.abs(delta)!=1)throw new IllegalArgumentException();int to=from+delta;if(to<0||to>=rows.size())return;
        JSONObject moved=rows.remove(from);rows.add(to,moved);JSONArray sorted=new JSONArray();for(int i=0;i<rows.size();i++){rows.get(i).put("sortOrder",i);sorted.put(rows.get(i));}d.put("practiceTypes",sorted);log(d,"practiceType","reorder",moved);
    }
    /** Commit the complete UI order once. Preserve IDs, row metadata and past snapshots. */
    public static void orderPracticeTypes(JSONObject d,List<String> ids)throws JSONException{
        List<JSONObject> before=options(d,"practiceType");Map<String,JSONObject> byId=new HashMap<>();
        List<String> existing=new ArrayList<>();for(JSONObject r:before){byId.put(r.getString("id"),r);existing.add(r.getString("id"));}
        if(ids.size()!=before.size()||new HashSet<>(ids).size()!=ids.size()||!byId.keySet().equals(new HashSet<>(ids)))throw new IllegalArgumentException("类型列表已变化，请重新排序");
        if(existing.equals(ids))return;
        JSONArray ordered=new JSONArray();for(int i=0;i<ids.size();i++){JSONObject r=byId.get(ids.get(i));r.put("sortOrder",i);ordered.put(r);}
        d.put("practiceTypes",ordered);if(!ids.isEmpty())log(d,"practiceType","reorder",byId.get(ids.get(0)));
    }
    public static void upsert(JSONObject d, String entity, JSONObject record) throws JSONException {
        validateRecord(entity, record);
        // Enforce the one-entry-per-day rule before making any mutation.
        if ("journal".equals(entity)) {
            JSONObject sameDay = journal(d, record.getString("journalDate"));
            if (sameDay != null && !sameDay.getString("id").equals(record.getString("id")))
                throw new IllegalArgumentException("这一天已有日记，请打开原日记修改");
        }
        if("practiceType".equals(entity))sortOptions(d,entity);
        JSONArray a = d.getJSONArray(array(entity)); String action = "create";JSONObject stored=copy(record);int oldMinutes=-1;
        for (int i = 0; i < a.length(); i++) if (a.getJSONObject(i).getString("id").equals(record.getString("id"))) {
            JSONObject previous=a.getJSONObject(i);if("durationPreset".equals(entity))oldMinutes=previous.getInt("minutes");
            if("journal".equals(entity)||"checkIn".equals(entity)){if(previous.has("createdAt"))stored.put("createdAt",previous.get("createdAt"));else stored.remove("createdAt");}
            if("practiceType".equals(entity))stored.put("sortOrder",i);
            a.remove(i); action = "update"; break;
        }
        if("practiceType".equals(entity)&&"create".equals(action))stored.put("sortOrder",a.length());
        a.put(stored);
        if("practiceType".equals(entity)||"durationPreset".equals(entity))sortOptions(d,entity);
        // Preserve a selected duration when an edit changes its only matching option.
        JSONObject checkDraft=d.getJSONObject("drafts").optJSONObject("checkIn");
        if("durationPreset".equals(entity)&&checkDraft!=null&&checkDraft.has("preset")){
            int selected=checkDraft.optInt("preset");boolean remains=false;for(JSONObject option:options(d,entity))if(option.optInt("minutes")==selected)remains=true;
            if(!remains&&selected==oldMinutes)checkDraft.put("preset",stored.getInt("minutes"));
        }
        log(d, entity, action, stored);
    }
    public static void delete(JSONObject d, String entity, String id) throws JSONException {
        JSONArray a = d.getJSONArray(array(entity));
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.getJSONObject(i);
            if (id.equals(r.getString("id"))) {
                JSONObject drafts = d.getJSONObject("drafts");
                // Archive an unfinished edit with the tombstone, rather than letting
                // a stale editor make a deleted entry appear to come back.
                String draftKey = "journal".equals(entity) ? "journal:" + r.getString("journalDate") : "dream";
                JSONObject pending = drafts.optJSONObject(draftKey);
                boolean related = "journal".equals(entity) || ("dream".equals(entity)
                    && pending != null && id.equals(pending.optString("id")));
                JSONObject tombstone = new JSONObject().put("entity", entity).put("deletedAt", now()).put("record", copy(r));
                if (related && pending != null) {
                    tombstone.put("archivedDraft", copy(pending)); drafts.remove(draftKey);
                }
                d.getJSONArray("deletedRecords").put(tombstone);
                log(d, entity, "delete", r); a.remove(i);
                JSONObject check=drafts.optJSONObject("checkIn");
                if(check!=null&&"practiceType".equals(entity)&&id.equals(check.optString("typeId")))check.remove("typeId");
                if(check!=null&&"durationPreset".equals(entity)&&check.optInt("preset",-1)==r.getInt("minutes")){
                    boolean remains=false;for(int j=0;j<a.length();j++)if(a.getJSONObject(j).getInt("minutes")==r.getInt("minutes"))remains=true;if(!remains)check.remove("preset");
                }
                if("practiceType".equals(entity)||"durationPreset".equals(entity))sortOptions(d,entity);return;
            }
        }
        throw new IllegalArgumentException("记录已不存在");
    }
    public static void restore(JSONObject d, int index) throws JSONException {
        JSONObject t = d.getJSONArray("deletedRecords").getJSONObject(index), r = t.getJSONObject("record");
        String e = t.getString("entity");
        if (find(d, e, r.getString("id")) != null || ("journal".equals(e) && journal(d, r.getString("journalDate")) != null))
            throw new IllegalArgumentException("存在同 ID 或同日期的记录，不能覆盖恢复");
        JSONObject archived = t.optJSONObject("archivedDraft");
        String draftKey = "journal".equals(e) ? "journal:" + r.getString("journalDate") : "dream";
        if (archived != null && d.getJSONObject("drafts").has(draftKey))
            throw new IllegalArgumentException("请先保存或放弃当前草稿，再恢复这条记录");
        d.getJSONArray(array(e)).put(copy(r));
        if (archived != null) d.getJSONObject("drafts").put(draftKey, copy(archived));
        log(d, e, "restore", r); d.getJSONArray("deletedRecords").remove(index);
    }
    public static boolean isEmpty(JSONObject d) throws JSONException {
        for (String a : ARRAYS) if (d.getJSONArray(a).length() > 0) return false;
        return d.getJSONArray("deletedRecords").length() == 0 && d.getJSONArray("auditLog").length() == 0 && d.getJSONObject("drafts").length() == 0;
    }
    public static String summary(JSONObject d) throws JSONException {
        return d.getJSONArray("practiceTypes").length()+" 种练习 · "+d.getJSONArray("durationPresets").length()+" 个常用时长\n"
            + d.getJSONArray("checkIns").length()+" 条打卡 · "+d.getJSONArray("journals").length()+" 篇感悟\n"
            + d.getJSONObject("drafts").length()+" 份未提交草稿 · "+d.getJSONArray("deletedRecords").length()+" 条可恢复删除\n"
            + d.getJSONArray("auditLog").length()+" 条操作历史";
    }
}
