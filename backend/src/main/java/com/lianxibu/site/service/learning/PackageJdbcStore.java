package com.lianxibu.site.service.learning;

import com.lianxibu.site.domain.protocol.CanonicalJson;
import com.lianxibu.site.domain.protocol.WireValues;
import com.lianxibu.site.repository.learning.JdbcLearningPackages;
import com.lianxibu.site.service.jobs.SharedWriteExecutor.ActionContext;
import com.lianxibu.site.service.learning.PackageLifecycleService.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import tools.jackson.databind.json.JsonMapper;

public final class PackageJdbcStore implements PackageLifecycleService.Store {
    private final JdbcLearningPackages store;private final String actor;private final Clock clock;
    public PackageJdbcStore(JdbcLearningPackages store,String actor,Clock clock){this.store=java.util.Objects.requireNonNull(store);this.actor=WireValues.id(actor,false);this.clock=java.util.Objects.requireNonNull(clock);}
    @Override public Result committed(String key,String hash){String value=store.receipt(actor,digest(key),HexFormat.of().parseHex(hash));if(value==null)return null;var j=CanonicalJson.parse(value);return new Result(j.get("package_id").asText(),j.get("revision").longValue(),j.get("state").asText(),true);}
    @Override public Current locked(String id){var row=store.read(id,true);return row==null?null:new Current(row.id(),row.revision(),row.state());}
    @Override public Validation validateLocked(Current row){return new Validation(row.packageId(),row.revision(),LearningPackageValidation.valid(store.items(row.packageId()),store.links(row.packageId())));}
    @Override public void transition(Current row,String target,String reason,String hash,ActionContext action){
        store.transition(row.packageId(),row.revision(),target,clock.instant());var j=JsonMapper.shared().createObjectNode();j.put("package_id",row.packageId());j.put("revision",row.revision()+1);j.put("state",target);j.put("reason",reason);
        store.receipt(actor,action.actionKey(),digest(action.actionKey()),HexFormat.of().parseHex(hash),j.toString(),clock.instant());
    }
    private static byte[] digest(String value){try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
