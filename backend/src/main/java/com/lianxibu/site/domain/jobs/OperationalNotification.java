package com.lianxibu.site.domain.jobs;

import java.nio.charset.StandardCharsets;

/** Controlled notification boundary. Acceptance proves a platform handoff, never recipient delivery. */
public final class OperationalNotification {
    private OperationalNotification() { }
    public enum Destination { LOG, CLOUD }
    /** Stable destination identity; credential rotation within the same group does not change it. */
    public record Channel(String fingerprint) {
        public Channel {if(fingerprint==null||!fingerprint.matches("[0-9a-f]{64}"))throw invalid();}
        public static Channel forAccount(String accountId,String group) {
            if(accountId==null||!accountId.matches("[0-9]{16}")||group==null||!group.matches("[A-Za-z0-9_-]{1,64}"))throw invalid();
            var value=tools.jackson.databind.json.JsonMapper.shared().createObjectNode().put("account_id",accountId).put("contact_group",group);
            try{return new Channel(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8))));}
            catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        }
    }
    public record Payload(String ruleName,String title,String message) {
        public Payload {
            if(ruleName==null||!ruleName.matches("OPS-(0[1-9]|10)"))throw invalid();
            safe(title,256);safe(message,8192);
            if(encode(ruleName,title,message).getBytes(StandardCharsets.UTF_8).length>8192)throw invalid();
        }
        public String json(){return encode(ruleName,title,message);}
        @Override public String toString(){return "NotificationPayload[redacted]";}
    }
    public record Receipt(String requestId) {
        public Receipt {if(requestId==null||!requestId.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))throw invalid();}
    }
    @FunctionalInterface public interface Sender { Receipt send(Payload payload) throws Exception; }
    public static void safe(String text,int max) {
        if(text==null||text.isBlank()||text.length()>max)throw invalid();
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(Character.isISOControl(c)||c=='\u2028'||c=='\u2029')throw invalid();
            if(Character.isHighSurrogate(c)){if(++i>=text.length()||!Character.isLowSurrogate(text.charAt(i)))throw invalid();}
            else if(Character.isLowSurrogate(c))throw invalid();
        }
    }
    private static String encode(String rule,String title,String message) {
        var value=tools.jackson.databind.json.JsonMapper.shared().createObjectNode();
        value.put("ruleName",rule).put("title",title).put("message",message);
        return value.toString();
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid notification payload");}
}
