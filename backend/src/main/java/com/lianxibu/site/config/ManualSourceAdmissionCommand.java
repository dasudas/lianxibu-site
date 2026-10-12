package com.lianxibu.site.config;

import com.lianxibu.site.repository.jobs.JdbcSharedWriteEntry;
import com.lianxibu.site.repository.sources.JdbcManualSourceAdmissions;
import com.lianxibu.site.repository.sources.JdbcSourceAdmission;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import com.lianxibu.site.service.jobs.SharedWriteExecutor;
import com.lianxibu.site.service.sources.ManualSourceAdmission;
import com.lianxibu.site.service.sources.ManualSourceAdmission.Manifest;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Host-only approval; credentials are injected, never command arguments or printed. */
public final class ManualSourceAdmissionCommand {
    private ManualSourceAdmissionCommand() { }
    public static Manifest read(Path path,String owners) throws Exception {
        byte[] bytes=boundedFile(path,65536);
        Manifest manifest=Manifest.parse(bytes);
        if(!BrowserIdentityConfiguration.ids(owners).contains(manifest.text("reviewer_id")))
            throw new IllegalArgumentException("not_authorized");
        for(String key:ManualSourceAdmission.EVIDENCE){
            String expected=manifest.evidenceHash(key);
            byte[] proof=boundedFile(path.getParent().resolve("evidence").resolve(expected),16*1024*1024);
            String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(proof));
            if(!actual.equals(expected))throw new IllegalArgumentException("invalid_manifest");
        }
        return manifest;
    }
    private static byte[] boundedFile(Path path,int limit) throws Exception {
        if(!path.isAbsolute()||!path.equals(path.normalize()))throw new IllegalArgumentException("invalid_manifest");
        for(Path parent=path;parent!=null;parent=parent.getParent())if(Files.isSymbolicLink(parent))throw new IllegalArgumentException("invalid_manifest");
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>limit)throw new IllegalArgumentException("invalid_manifest");
        try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){
            byte[] value=in.readNBytes(limit+1);if(value.length>limit)throw new IllegalArgumentException("invalid_manifest");return value;
        }
    }
    public static void main(String[] args){
        try{
            if(args.length!=2||!("approve".equals(args[0])||"revoke".equals(args[0])))throw new IllegalArgumentException();
            Manifest proof=read(Path.of(args[1]),System.getenv().getOrDefault("SITE_OWNER_IDS",""));
            String url=System.getenv("LIANXIBU_DB_URL"),user=System.getenv("LIANXIBU_DB_USERNAME"),password=System.getenv("LIANXIBU_DB_PASSWORD");
            if(url==null||!url.startsWith("jdbc:mysql://")||user==null||password==null)throw new IllegalArgumentException();
            var source=new DriverManagerDataSource(url,user,password);var tx=new DataSourceTransactionManager(source);
            var service=new ManualSourceAdmission(new JdbcManualSourceAdmissions(source),new JdbcSourceAdmission(source),
                    new SharedWriteExecutor(new JdbcSharedWriteEntry(source),tx),Clock.systemUTC());
            service.apply(new Actor(proof.text("reviewer_id"),"owner"),args[0],proof);
            System.out.println("Manual source decision committed.");
        }catch(Exception failed){System.err.println("Manual source decision failed; verify host configuration and reviewed evidence.");System.exit(1);}
    }
}
