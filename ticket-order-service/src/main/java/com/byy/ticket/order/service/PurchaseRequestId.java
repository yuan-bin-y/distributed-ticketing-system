package com.byy.ticket.order.service;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

/** 同一用户与幂等键跨进程得到相同编号；不使用本机内存或每次随机 UUID。 */
public final class PurchaseRequestId {
    private PurchaseRequestId(){}
    public static String of(long userId,String key){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((userId+":"+key).getBytes(StandardCharsets.UTF_8))).substring(0,32);}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
