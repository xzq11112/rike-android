package app.rike.offline;

import org.json.JSONObject;
import java.time.*;
import java.time.format.DateTimeFormatter;

/** Read existing timestamps without inventing a first-save time for old data. */
final class RecordTime {
    static Instant created(JSONObject row){
        Object value=row.opt("createdAt");
        try{
            if(value instanceof Number)return Instant.ofEpochMilli(((Number)value).longValue());
            if(value instanceof String){
                String s=(String)value;
                try{return Instant.parse(s);}catch(Exception ignored){}
                try{return OffsetDateTime.parse(s).toInstant();}catch(Exception ignored){}
                return LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant();
            }
        }catch(Exception ignored){}
        return null;
    }
    static String label(JSONObject row){
        Instant time=created(row);
        return time==null?"时间未记录":DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(time);
    }
}
