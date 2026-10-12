package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.jobs.RdsTelemetry.*;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Three bounded read-only requests, without retries, redirects, SQL or diagnostic response bodies. */
public final class AliyunRdsMetricsClient implements Api {
    public record Credentials(String accessKeyId,String accessKeySecret) {
        public Credentials {
            if(accessKeyId==null||!accessKeyId.matches("[A-Za-z0-9]{16,64}")
                    ||accessKeySecret==null||!accessKeySecret.matches("[A-Za-z0-9]{20,128}"))
                throw new IllegalArgumentException("monitoring credentials unavailable");
        }
        @Override public String toString(){return "MonitoringCredentials[redacted]";}
    }
    record Signed(String query,Map<String,String> headers) {
        @Override public String toString(){return "SignedRequest[redacted]";}
    }
    private static final URI CMS=URI.create("https://metrics.cn-hangzhou.aliyuncs.com/");
    private static final URI RDS=URI.create("https://rds.aliyuncs.com/");
    private static final String EMPTY_HASH=sha(new byte[0]);
    private final HttpClient http;
    private final URI cms,rds;
    private final Supplier<Credentials> credentials;
    private final Clock clock;
    private final Duration deadline;
    public AliyunRdsMetricsClient(Supplier<Credentials> credentials,Clock clock) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build(),CMS,RDS,credentials,clock,Duration.ofSeconds(20));
    }
    AliyunRdsMetricsClient(HttpClient http,URI cms,URI rds,Supplier<Credentials> credentials,Clock clock,Duration deadline) {
        if(http==null||http.followRedirects()!=HttpClient.Redirect.NEVER||credentials==null||clock==null
                ||deadline==null||deadline.isNegative()||deadline.isZero()||deadline.compareTo(Duration.ofSeconds(20))>0
                ||!valid(cms,CMS)||!valid(rds,RDS))throw new IllegalArgumentException("bounded monitoring transport required");
        this.http=http;this.cms=cms;this.rds=rds;this.credentials=credentials;this.clock=clock;this.deadline=deadline;
    }
    private static boolean valid(URI value,URI official) {
        return value!=null&&value.getRawUserInfo()==null&&value.getRawQuery()==null&&value.getRawFragment()==null&&"/".equals(value.getRawPath())
                &&(value.equals(official)||"http".equals(value.getScheme())&&"localhost".equals(value.getHost())&&value.getPort()>0);
    }
    @Override public Evidence read(Resource resource,Instant queryAt) {
        if(resource==null||queryAt==null)throw new IllegalArgumentException("monitoring identity required");
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("monitoring network outside transaction required");
        long end=System.nanoTime()+deadline.toNanos();
        try {
            String attributes=request(rds,"DescribeDBInstanceAttribute","2014-08-15",Map.of("DBInstanceId",resource.instanceId()),end);
            String metadata=request(cms,"DescribeMetricMetaList","2019-01-01",Map.of("Namespace","acs_rds_dashboard","MetricName","DiskUsage","PageSize","1","PageNumber","1"),end);
            String dimensions="[{\"userId\":\""+resource.accountId()+"\",\"instanceId\":\""+resource.instanceId()+"\"}]";
            String metrics=request(cms,"DescribeMetricList","2019-01-01",Map.of("Namespace","acs_rds_dashboard","MetricName","DiskUsage","Dimensions",dimensions,"Period","60","Length","30","StartTime",Long.toString(queryAt.truncatedTo(ChronoUnit.MINUTES).minusSeconds(480).toEpochMilli()),"EndTime",Long.toString(queryAt.toEpochMilli())),end);
            return new Evidence(attributes,metadata,metrics,clock.instant());
        } catch(Unavailable|RuntimeException invalid) { return null; }
    }
    private String request(URI endpoint,String action,String version,Map<String,String> parameters,long end)throws Unavailable {
        if(Thread.currentThread().isInterrupted())throw new Unavailable();
        long remaining=Math.min(TimeUnit.SECONDS.toNanos(8),end-System.nanoTime());if(remaining<=0)throw new Unavailable();
        Credentials key=credentials.get();if(key==null)throw new Unavailable();
        Signed signed=sign(endpoint.getRawAuthority(),action,version,parameters,key,clock.instant(),UUID.randomUUID().toString());
        HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create(endpoint.toString()+"?"+signed.query())).timeout(Duration.ofNanos(remaining)).header("Accept","application/json");
        signed.headers().forEach(builder::header);
        var pending=http.sendAsync(builder.POST(HttpRequest.BodyPublishers.noBody()).build(),info->{
            long declared=info.headers().firstValueAsLong("content-length").orElse(-1);
            return new BoundedBody(info.statusCode()!=200||declared>262144);
        });
        try {
            var response=pending.get(Math.max(1,Math.min(remaining,end-System.nanoTime())),TimeUnit.NANOSECONDS);
            if(response.statusCode()!=200)throw new Unavailable();
            byte[] bytes=response.body();long declared=response.headers().firstValueAsLong("content-length").orElse(-1);
            if(declared>=0&&declared!=bytes.length)throw new Unavailable();
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch(InterruptedException interrupted){pending.cancel(true);Thread.currentThread().interrupt();throw new Unavailable();}
        catch(ExecutionException|TimeoutException|CancellationException|CharacterCodingException failed){pending.cancel(true);throw new Unavailable();}
    }
    static Signed sign(String host,String action,String version,Map<String,String> parameters,Credentials key,Instant at,String nonce) {
        var values=new TreeMap<String,String>(); parameters.forEach((name,value)->values.put(encode(name),encode(value)));
        String query=values.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).collect(java.util.stream.Collectors.joining("&"));
        var headers=new TreeMap<String,String>();headers.put("host",host);headers.put("x-acs-action",action);
        headers.put("x-acs-content-sha256",EMPTY_HASH);headers.put("x-acs-date",at.truncatedTo(ChronoUnit.SECONDS).toString());headers.put("x-acs-signature-nonce",nonce);headers.put("x-acs-version",version);
        String names=String.join(";",headers.keySet());String canonicalHeaders=headers.entrySet().stream().map(e->e.getKey()+":"+e.getValue()+"\n").collect(java.util.stream.Collectors.joining());
        String canonical="POST\n/\n"+query+"\n"+canonicalHeaders+"\n"+names+"\n"+EMPTY_HASH;
        String toSign="ACS3-HMAC-SHA256\n"+sha(canonical.getBytes(StandardCharsets.UTF_8));
        try {
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key.accessKeySecret().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            String signature=HexFormat.of().formatHex(mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8)));
            headers.remove("host");headers.put("Authorization","ACS3-HMAC-SHA256 Credential="+key.accessKeyId()+",SignedHeaders="+names+",Signature="+signature);
            return new Signed(query,Map.copyOf(headers));
        }catch(java.security.GeneralSecurityException unavailable){throw new IllegalStateException("monitoring signature unavailable");}
    }
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20").replace("*","%2A").replace("%7E","~");}
    private static String sha(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(java.security.NoSuchAlgorithmException unavailable){throw new IllegalStateException("monitoring signature unavailable");}}
    private static final class Unavailable extends Exception { }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body=new CompletableFuture<>();private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final boolean rejected;private Flow.Subscription subscription;
        BoundedBody(boolean rejected){this.rejected=rejected;}
        @Override public CompletionStage<byte[]> getBody(){return body;}
        @Override public void onSubscribe(Flow.Subscription value){if(subscription!=null){value.cancel();return;}subscription=value;if(rejected){value.cancel();body.completeExceptionally(new Unavailable());}else value.request(1);}
        @Override public void onNext(List<ByteBuffer> buffers){if(body.isDone()){subscription.cancel();return;}for(var buffer:buffers){if(buffer.remaining()>262144-bytes.size()){subscription.cancel();body.completeExceptionally(new Unavailable());return;}byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);}subscription.request(1);}
        @Override public void onError(Throwable failure){body.completeExceptionally(failure);}
        @Override public void onComplete(){body.complete(bytes.toByteArray());}
    }
}
