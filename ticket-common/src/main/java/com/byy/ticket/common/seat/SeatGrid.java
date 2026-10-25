package com.byy.ticket.common.seat;
import java.util.*;
/** 不同服务复用相同的静态网格算法，各自保存自己负责的数据，不共享数据库。 */
public final class SeatGrid {
    private SeatGrid(){}
    public record Region(String code,int rows,int columns){}
    public record Seat(String id,String region,int row,int column,int ordinal){}
    public static List<Region> validate(List<Region> regions){
        if(regions==null||regions.isEmpty()||regions.size()>20)throw new IllegalArgumentException("请配置1至20个座位区域");
        var codes=new HashSet<String>();long total=0;
        for(var region:regions){if(region==null||region.code()==null||!region.code().matches("[A-Za-z0-9_-]{1,20}")||!codes.add(region.code())
            ||region.rows()<1||region.rows()>500||region.columns()<1||region.columns()>500)throw new IllegalArgumentException("区域代码须唯一，行列范围须为1至500");total+=(long)region.rows()*region.columns();}
        if(total>20000)throw new IllegalArgumentException("每票档最多配置20000个座位");
        return regions.stream().sorted(Comparator.comparing(Region::code)).toList();
    }
    public static int total(List<Region> regions){return validate(regions).stream().mapToInt(r->r.rows()*r.columns()).sum();}
    public static String hash(List<Region> regions){var sorted=validate(regions);return SeatSelection.sha256(sorted.stream().map(r->r.code()+":"+r.rows()+":"+r.columns()).collect(java.util.stream.Collectors.joining("\n")));}
    public static List<Seat> seats(List<Region> regions){List<Seat> result=new ArrayList<>();int ordinal=0;for(var region:validate(regions))for(int row=1;row<=region.rows();row++)for(int column=1;column<=region.columns();column++)result.add(new Seat(region.code()+":"+row+":"+column,region.code(),row,column,++ordinal));return result;}
}
