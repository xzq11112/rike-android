package app.rike.offline;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Display-only actual minutes. Never changes data, backup values or statistics. */
public final class PracticeHeatmap {
    private PracticeHeatmap() {}
    public static final class Day {
        public final LocalDate date;
        public final long minutes;
        private Day(LocalDate date,long minutes){this.date=date;this.minutes=minutes;}
    }
    // Fixed absolute anchors: never normalize against a user's largest value.
    static final long[] ANCHORS={1,15,30,60,120,240};
    static final int[] YELLOWS={0xfffffae0,0xffffefad,0xffffe27a,0xffffd34e,0xfff5b82b,0xffdc9815};
    public static int color(long minutes,boolean dark){
        if(minutes<=0)return dark?0xff302e2a:0xffe8e4da;
        if(minutes<=ANCHORS[0])return YELLOWS[0];
        for(int i=1;i<ANCHORS.length;i++)if(minutes<=ANCHORS[i]){
            double t=(minutes-ANCHORS[i-1])/(double)(ANCHORS[i]-ANCHORS[i-1]);
            int a=YELLOWS[i-1],b=YELLOWS[i],result=0xff000000;
            for(int shift:new int[]{16,8,0})result|=(int)Math.round(((a>>shift)&255)*(1-t)+((b>>shift)&255)*t)<<shift;
            return result;
        }
        return YELLOWS[YELLOWS.length-1];
    }
    private static List<Day> range(LocalDate start,LocalDate end,Map<String,Long> minutes){
        List<Day> days=new ArrayList<>();
        for(LocalDate date=start;date.isBefore(end);date=date.plusDays(1))
            days.add(new Day(date,minutes.getOrDefault(date.toString(),0L)));
        return Collections.unmodifiableList(days);
    }
    public static List<Day> year(int year,Map<String,Long> minutes){
        return range(LocalDate.of(year,1,1),LocalDate.of(year+1,1,1),minutes);
    }
    public static List<Day> month(int year,int month,Map<String,Long> minutes){
        YearMonth value=YearMonth.of(year,month);
        return range(value.atDay(1),value.plusMonths(1).atDay(1),minutes);
    }
}
