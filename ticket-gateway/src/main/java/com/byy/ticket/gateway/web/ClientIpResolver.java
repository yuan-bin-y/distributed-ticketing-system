package com.byy.ticket.gateway.web;

import java.net.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

/** 保留原始远端地址，只在直连来源可信时从右向左剥离代理链。 */
@Component
public class ClientIpResolver {
    private final List<Network> proxies;
    public ClientIpResolver(@Value("${ticket.rate-limit.trusted-proxies:}") String trustedProxies) {
        List<Network> parsed=new ArrayList<>();
        for(String item:trustedProxies.split(",")) {
            if(item.isBlank())continue;
            String[] parts=item.trim().split("/",-1);String address=literal(parts[0]);
            if(address==null || parts.length>2)throw new IllegalArgumentException("可信代理必须是IP或CIDR");
            if(parts.length==2 && (!parts[1].matches("[0-9]{1,3}") || Integer.parseInt(parts[1])==0))
                throw new IllegalArgumentException("可信代理CIDR不能为空或覆盖全部地址");
            try {
                byte[] bytes=InetAddress.getByName(address).getAddress();
                int bits=parts.length==2?Integer.parseInt(parts[1]):bytes.length*8;
                if(bits>bytes.length*8)throw new IllegalArgumentException("可信代理CIDR前缀超出地址长度");
                parsed.add(new Network(bytes,bits));
            }catch(UnknownHostException impossible){throw new IllegalArgumentException("可信代理地址无效",impossible);}
        }
        proxies=List.copyOf(parsed);
    }
    public String resolve(ServerHttpRequest request) {
        var remote=request.getRemoteAddress();
        if(remote==null || remote.getAddress()==null)return "unknown";
        String peer=remote.getAddress().getHostAddress();
        if(!trusted(peer))return peer;
        var headers=request.getHeaders().get("X-Forwarded-For");
        if(headers==null || headers.size()!=1)return peer;
        String raw=headers.get(0);
        if(raw==null || raw.length()>2048)return peer;
        String[] chain=raw.split(",",-1);
        if(chain.length>20)return peer;
        List<String> addresses=new ArrayList<>();
        for(String entry:chain) {
            String address=literal(entry.trim());
            if(address==null)return peer;
            addresses.add(address);
        }
        String current=peer;
        for(int i=addresses.size()-1;i>=0 && trusted(current);i--)current=addresses.get(i);
        return current;
    }
    private boolean trusted(String value) {return proxies.stream().anyMatch(proxy->proxy.matches(value));}
    /** 按地址字节比较CIDR，网关不引入Servlet类型。 */
    private record Network(byte[] address,int bits) {
        boolean matches(String value) {
            try {
                byte[] candidate=InetAddress.getByName(value).getAddress();
                if(candidate.length!=address.length)return false;
                for(int bit=0;bit<bits;bit++)
                    if(((address[bit/8]^candidate[bit/8]) & (1<<(7-bit%8)))!=0)return false;
                return true;
            }catch(UnknownHostException invalid){return false;}
        }
    }
    /** 仅解析数字地址，不对请求头里的主机名执行DNS查询。 */
    private String literal(String value) {
        try {
            if(value.matches("[0-9]+(?:\\.[0-9]+){3}")) {
                String[] parts=value.split("\\.");byte[] bytes=new byte[4];
                for(int i=0;i<4;i++) {int part=Integer.parseInt(parts[i]);if(part>255)return null;bytes[i]=(byte)part;}
                return InetAddress.getByAddress(bytes).getHostAddress();
            }
            if(value.contains(":") && value.matches("[a-fA-F0-9:.]+"))return InetAddress.getByName(value).getHostAddress();
        }catch(IllegalArgumentException|UnknownHostException invalid) {return null;}
        return null;
    }
}
