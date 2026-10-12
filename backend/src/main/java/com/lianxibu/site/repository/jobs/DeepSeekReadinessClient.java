package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.jobs.ProviderReadinessTelemetry;
import com.lianxibu.site.domain.jobs.ProviderReadinessTelemetry.Evidence;
import com.lianxibu.site.domain.jobs.ProviderReadinessTelemetry.Status;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;

/** Two bounded authenticated GETs. No generation, transport retry, body logging or SQL. */
public final class DeepSeekReadinessClient implements ProviderReadinessTelemetry.Api {
    private static final int MAX_BYTES=262144;
    private final HttpClient http;private final URI base;private final Supplier<String> credential;private final Clock clock;private final Duration timeout;
    public DeepSeekReadinessClient(Supplier<String> credential,Clock clock) {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build(),URI.create("https://api.deepseek.com"),credential,clock,Duration.ofSeconds(12));
    }
    DeepSeekReadinessClient(HttpClient http,URI base,Supplier<String> credential,Clock clock,Duration timeout) {
        if(http==null||http.followRedirects()!=HttpClient.Redirect.NEVER||base==null||credential==null||clock==null||timeout==null
                ||timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofSeconds(12))>0
                ||!("https".equals(base.getScheme())&&"api.deepseek.com".equals(base.getHost())&&(base.getPort()==-1||base.getPort()==443)
                    ||"http".equals(base.getScheme())&&"localhost".equals(base.getHost())&&base.getPort()>0)
                ||!(base.getRawPath().isEmpty()||"/".equals(base.getRawPath()))||base.getRawUserInfo()!=null||base.getRawQuery()!=null||base.getRawFragment()!=null)
            throw new IllegalArgumentException("invalid provider readiness transport");
        this.http=http;this.base=base;this.credential=credential;this.clock=clock;this.timeout=timeout;
    }
    @Override public Evidence read() {
        if(TransactionSynchronizationManager.isActualTransactionActive()||Thread.currentThread().isInterrupted())return null;
        try {
            String key=credential.get();if(key==null||key.isBlank()||key.length()>8192||key.chars().anyMatch(c->c<33||c>126))return null;
            long started=System.nanoTime();
            var models=get("/models",key,started);Status status=explicit(models);
            if(status==null) {
                if(models.statusCode()!=200)return null;
                Boolean present=model(models.body());if(present==null)return null;
                if(!present)status=Status.MODEL_UNAVAILABLE;
                else {
                    var balance=get("/user/balance",key,started);status=explicit(balance);
                    if(status==null) {
                        if(balance.statusCode()!=200)return null;
                        Boolean available=balance(balance.body());if(available==null)return null;
                        status=available?Status.READY:Status.BALANCE_UNAVAILABLE;
                    }
                }
            }
            if(Thread.currentThread().isInterrupted()||System.nanoTime()-started>=timeout.toNanos())return null;
            return new Evidence(clock.instant().truncatedTo(ChronoUnit.MICROS),status);
        } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();return null;}
        catch(Exception unavailable) {return null;}
    }
    private HttpResponse<byte[]> get(String path,String key,long started) throws Exception {
        long remaining=timeout.toNanos()-(System.nanoTime()-started);if(remaining<=0)throw new TimeoutException();
        var request=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofNanos(remaining)).header("Authorization","Bearer "+key).header("Accept","application/json").GET().build();
        var pending=http.sendAsync(request,ignored->new BoundedBody());
        try {
            var response=pending.get(Math.max(1,timeout.toNanos()-(System.nanoTime()-started)),TimeUnit.NANOSECONDS);
            long declared=response.headers().firstValueAsLong("content-length").orElse(-1);
            if(declared>MAX_BYTES||declared>=0&&declared!=response.body().length)throw new IllegalArgumentException();
            return response;
        } catch(Exception failure) {pending.cancel(true);throw failure;}
    }
    private static Status explicit(HttpResponse<byte[]> response) {
        return switch(response.statusCode()) {case 401,403->Status.CREDENTIAL_REJECTED;case 402->Status.BALANCE_UNAVAILABLE;default->null;};
    }
    private static Boolean model(byte[] bytes) {
        var root=CanonicalJson.parse(bytes);if(!root.isObject()||!"list".equals(root.path("object").asText())||!root.path("data").isArray()||root.path("data").size()>256)return null;
        boolean found=false;var ids=new HashSet<String>();
        for(var item:root.get("data")) {
            if(!item.isObject()||!"model".equals(item.path("object").asText())||!item.path("id").isTextual()||!item.path("owned_by").isTextual())return null;
            String id=item.get("id").asText();if(!id.matches("[A-Za-z0-9._:-]{1,128}")||!ids.add(id)||item.get("owned_by").asText().isBlank())return null;
            if("deepseek-flash".equals(id)){if(!"deepseek".equals(item.get("owned_by").asText()))return null;found=true;}
        }
        return found;
    }
    private static Boolean balance(byte[] bytes) {
        var root=CanonicalJson.parse(bytes);if(!root.isObject()||!root.path("is_available").isBoolean()||!root.path("balance_infos").isArray()||root.path("balance_infos").size()>8)return null;
        boolean positive=false;var currencies=new HashSet<String>();
        for(var item:root.get("balance_infos")) {
            if(!item.isObject()||!item.path("currency").isTextual()||!Set.of("CNY","USD").contains(item.get("currency").asText())||!currencies.add(item.get("currency").asText()))return null;
            for(String field:List.of("total_balance","granted_balance","topped_up_balance")) {
                var value=item.get(field);if(value==null||!value.isTextual()||!value.asText().matches("[0-9]{1,20}(\\.[0-9]{1,20})?"))return null;
                var amount=new BigDecimal(value.asText());if("total_balance".equals(field)&&amount.signum()>0)positive=true;
            }
        }
        boolean available=root.get("is_available").booleanValue();return available&&!positive?null:available;
    }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody(){return result;}
        @Override public void onSubscribe(Flow.Subscription value){if(subscription!=null){value.cancel();return;}subscription=value;value.request(1);}
        @Override public void onNext(List<ByteBuffer> buffers){
            if(result.isDone()){subscription.cancel();return;}
            for(var buffer:buffers){if(buffer.remaining()>MAX_BYTES-bytes.size()){subscription.cancel();result.completeExceptionally(new IllegalArgumentException("readiness response exceeds capacity"));return;}byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);}subscription.request(1);
        }
        @Override public void onError(Throwable failure){result.completeExceptionally(failure);}
        @Override public void onComplete(){result.complete(bytes.toByteArray());}
    }
}
