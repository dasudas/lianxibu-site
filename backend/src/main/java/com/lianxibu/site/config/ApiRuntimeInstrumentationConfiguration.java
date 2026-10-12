package com.lianxibu.site.config;
import com.lianxibu.site.repository.jobs.ProcessApiRuntimeTelemetry;import jakarta.servlet.*;import jakarta.servlet.http.*;import java.io.IOException;import java.util.Objects;import java.util.concurrent.atomic.AtomicBoolean;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.springframework.boot.web.servlet.FilterRegistrationBean;import org.springframework.context.SmartLifecycle;import org.springframework.context.annotation.*;import org.springframework.core.Ordered;import org.springframework.web.filter.OncePerRequestFilter;import org.springframework.web.method.HandlerMethod;import org.springframework.web.servlet.HandlerMapping;
/** Counts completed mapped API handlers without retaining request paths, headers or payloads. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"site.ops.api-monitoring.enabled","site.browser.enabled"},havingValue="true")
public class ApiRuntimeInstrumentationConfiguration {
 @Bean NativeCapture apiRuntimeNativeCapture(){return new NativeCapture(ProcessApiRuntimeTelemetry.nativeProcess());}
 @Bean FilterRegistrationBean<CompletionFilter> apiRuntimeCompletionFilter(NativeCapture capture){var filter=new FilterRegistrationBean<>(new CompletionFilter(capture.probe));filter.setName("native-api-completion");filter.addUrlPatterns("/*");filter.setDispatcherTypes(DispatcherType.REQUEST);filter.setAsyncSupported(true);filter.setOrder(Ordered.HIGHEST_PRECEDENCE+20);return filter;}
 public static final class NativeCapture implements SmartLifecycle {
  final ProcessApiRuntimeTelemetry probe;private ProcessApiRuntimeTelemetry.Capture capture;
  NativeCapture(ProcessApiRuntimeTelemetry probe){this.probe=Objects.requireNonNull(probe);}
  @Override public synchronized void start(){if(capture==null)capture=probe.openCapture();}
  @Override public synchronized void stop(){if(capture!=null){capture.close();capture=null;}}
  @Override public void stop(Runnable callback){stop();callback.run();}
  @Override public synchronized boolean isRunning(){return capture!=null;}@Override public int getPhase(){return Integer.MIN_VALUE+200;}
  @Override public String toString(){return "ApiRuntimeNativeCapture[redacted]";}
 }
 public static final class CompletionFilter extends OncePerRequestFilter {
  private final ProcessApiRuntimeTelemetry probe;CompletionFilter(ProcessApiRuntimeTelemetry probe){this.probe=Objects.requireNonNull(probe);}
  @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
   boolean uncaught=false;try{chain.doFilter(request,response);}catch(IOException|ServletException|RuntimeException failure){uncaught=true;throw failure;}finally{
    Object handler=request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);if(handler instanceof HandlerMethod method&&!method.getBeanType().getName().startsWith("org.springframework.boot.")){
     var recorded=new AtomicBoolean();final boolean failed=uncaught;Runnable complete=()->{if(recorded.compareAndSet(false,true))probe.completedHttp(failed&&!response.isCommitted()&&response.getStatus()<500?500:response.getStatus());};
     if(request.isAsyncStarted()){var listener=new AsyncListener(){@Override public void onComplete(AsyncEvent event){complete.run();}@Override public void onError(AsyncEvent event){}@Override public void onTimeout(AsyncEvent event){}@Override public void onStartAsync(AsyncEvent event){if(!recorded.get())event.getAsyncContext().addListener(this);}};try{request.getAsyncContext().addListener(listener);}catch(IllegalStateException alreadyComplete){complete.run();}}
     else complete.run();
    }
   }
  }
 }
}
