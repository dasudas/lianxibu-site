package com.lianxibu.site.repository.jobs;

import com.lianxibu.site.domain.jobs.OperationalNotification.*;
import com.lianxibu.site.domain.protocol.CanonicalJson;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One bounded platform handoff to the fixed official endpoint. Never logs authenticated URLs or responses. */
public final class AliyunNotificationClient implements Sender {
    public record Credentials(String accountId,String contactGroup,String token) {
        public Credentials {
            try {
                if(accountId==null||!accountId.matches("[0-9]{16}")||contactGroup==null||!contactGroup.matches("[A-Za-z0-9_-]{1,64}")
                        ||token==null||token.length()>4096||!token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))throw new IllegalArgumentException();
                var claims=CanonicalJson.parse(Base64.getUrlDecoder().decode(token.substring(0,token.indexOf('.'))));
                if(!claims.isObject()||claims.size()!=4||!accountId.equals(claims.path("aliUid").asString())
                        ||!contactGroup.equals(claims.path("name").asString())||!"contactGroup".equals(claims.path("tokenType").asString())
                        ||!claims.path("secure").isString()||!claims.get("secure").asString().matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))throw new IllegalArgumentException();
            }catch(RuntimeException invalid){throw new IllegalArgumentException("notification credentials unavailable");}
        }
        @Override public String toString(){return "NotificationCredentials[redacted]";}
    }
    private static final URI ENDPOINT=URI.create("https://metrichub-cms-cn-hangzhou.aliyuncs.com/event/notify");
    private final HttpClient http;private final URI endpoint;private final Supplier<Credentials> credentials;private final Duration deadline;
    public AliyunNotificationClient(Supplier<Credentials> credentials) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build(),ENDPOINT,credentials,Duration.ofSeconds(8));
    }
    AliyunNotificationClient(HttpClient http,URI endpoint,Supplier<Credentials> credentials,Duration deadline) {
        if(http==null||http.followRedirects()!=HttpClient.Redirect.NEVER||credentials==null||deadline==null||deadline.isZero()||deadline.isNegative()||deadline.compareTo(Duration.ofSeconds(8))>0
                ||endpoint==null||endpoint.getRawUserInfo()!=null||endpoint.getRawQuery()!=null||endpoint.getRawFragment()!=null||!"/event/notify".equals(endpoint.getRawPath())
                ||!(ENDPOINT.equals(endpoint)||"http".equals(endpoint.getScheme())&&"localhost".equals(endpoint.getHost())&&endpoint.getPort()>0))throw new IllegalArgumentException("bounded notification transport required");
        this.http=http;this.endpoint=endpoint;this.credentials=credentials;this.deadline=deadline;
    }
    @Override public Receipt send(Payload payload) {
        Objects.requireNonNull(payload);
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("notification network outside transaction required");
        if(Thread.currentThread().isInterrupted())return null;
        long end=System.nanoTime()+deadline.toNanos();CompletableFuture<HttpResponse<byte[]>> pending=null;
        try {
            var key=credentials.get();if(key==null)return null;
            var request=HttpRequest.newBuilder(URI.create(endpoint+"?token="+key.token()+"&level=INFO")).timeout(deadline)
                    .header("Content-Type","application/json; charset=utf-8").header("Accept","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.json(),StandardCharsets.UTF_8)).build();
            pending=http.sendAsync(request,info->new BoundedBody(info.statusCode()!=200||info.headers().firstValueAsLong("content-length").orElse(-1)>16384));
            var response=pending.get(Math.max(1,end-System.nanoTime()),TimeUnit.NANOSECONDS);
            if(response.statusCode()!=200)return null;
            byte[] bytes=response.body();long declared=response.headers().firstValueAsLong("content-length").orElse(-1);if(declared>=0&&declared!=bytes.length)return null;
            var value=CanonicalJson.parse(bytes);var code=value.path("code");
            if(!value.isObject()||!(code.isString()&&"200".equals(code.asString())||code.isIntegralNumber()&&code.asLong()==200)||!value.path("requestId").isString())return null;
            var receipt=new Receipt(value.get("requestId").asString());
            var data=value.path("data");if(!data.isObject()||!data.path("requestId").isString()||!receipt.requestId().equals(data.get("requestId").asString()))return null;
            return receipt;
        }catch(InterruptedException interrupted){if(pending!=null)pending.cancel(true);Thread.currentThread().interrupt();return null;}
        catch(ExecutionException|TimeoutException|RuntimeException failed){if(pending!=null)pending.cancel(true);return null;}
    }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body=new CompletableFuture<>();private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final boolean rejected;private Flow.Subscription subscription;
        BoundedBody(boolean rejected){this.rejected=rejected;}
        @Override public CompletionStage<byte[]> getBody(){return body;}
        @Override public void onSubscribe(Flow.Subscription value){if(subscription!=null){value.cancel();return;}subscription=value;if(rejected){value.cancel();body.completeExceptionally(new IllegalStateException("notification response unavailable"));}else value.request(1);}
        @Override public void onNext(List<ByteBuffer> buffers){if(body.isDone()){subscription.cancel();return;}for(var buffer:buffers){if(buffer.remaining()>16384-bytes.size()){subscription.cancel();body.completeExceptionally(new IllegalStateException("notification response unavailable"));return;}byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);}subscription.request(1);}
        @Override public void onError(Throwable failed){body.completeExceptionally(failed);}
        @Override public void onComplete(){body.complete(bytes.toByteArray());}
    }
}
