package com.byy.ticket.common.seat;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
/** 规范化座位标识；请求重排不改变幂等含义，重复座位必须拒绝。 */
public final class SeatSelection {
    private SeatSelection(){}
    public static List<String> normalize(List<String> input,Integer quantity){
        if(input==null||input.isEmpty())return List.of();
        if(input.size()>100||quantity==null||input.size()!=quantity)throw new IllegalArgumentException("选座数量须与购票数量一致，单次最多100个座位");
        var sorted=new TreeSet<String>();
        for(String seat:input)if(seat==null||!seat.matches("[A-Za-z0-9_-]{1,20}:[1-9][0-9]{0,2}:[1-9][0-9]{0,2}")||!sorted.add(seat))throw new IllegalArgumentException("座位编号格式无效或重复");
        return List.copyOf(sorted);
    }
    public static String fingerprint(List<String> seats){return seats==null||seats.isEmpty()?"":sha256(String.join("\n",seats));}
    public static String encode(List<String> seats){return String.join(",",seats);}
    public static List<String> decode(String value){if(value==null||value.isEmpty())return List.of();var result=List.of(value.split(","));return normalize(result,result.size());}
    public static String sha256(String input){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
