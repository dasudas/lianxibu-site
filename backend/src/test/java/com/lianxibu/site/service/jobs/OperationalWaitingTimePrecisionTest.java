package com.lianxibu.site.service.jobs;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;import java.util.*;import org.junit.jupiter.api.Test;
class OperationalWaitingTimePrecisionTest {
 private final Instant start=Instant.parse("2026-01-01T17:00:00Z");
 @Test void distinctHalfSecondEligibleIntervalsAreSummedBeforeTheSingleFinalFloor(){
  var spans=List.of(new OperationalWaitingTime.Span(start,start.plusMillis(500),true,false),new OperationalWaitingTime.Span(start.plusMillis(750),start.plusMillis(1250),true,false));
  assertEquals(1,OperationalWaitingTime.eligibleSeconds(spans));
 }
 @Test void ActualShanghaiWindowClippingAndPauseExclusionsRetainFractionalPrecision(){
  var before=start.minusMillis(400);var end=start.plusSeconds(6*3600);
  var spans=List.of(new OperationalWaitingTime.Span(before,start.plusMillis(600),true,false),new OperationalWaitingTime.Span(start.plusMillis(600),start.plusSeconds(1),true,true),new OperationalWaitingTime.Span(end.minusMillis(400),end.plusMillis(600),true,false));
  assertEquals(1,OperationalWaitingTime.eligibleSeconds(spans));
  assertEquals(0,OperationalWaitingTime.eligibleSeconds(List.of(new OperationalWaitingTime.Span(start,start.plusMillis(999),true,false))));
 }
 @Test void OverlapNullAndUnboundedIntervalsRemainRejected(){
  var span=new OperationalWaitingTime.Span(start,start.plusSeconds(1),true,false);
  assertThrows(IllegalArgumentException.class,()->OperationalWaitingTime.eligibleSeconds(List.of(span,span)));
  assertThrows(IllegalArgumentException.class,()->OperationalWaitingTime.eligibleSeconds(null));
  assertThrows(IllegalArgumentException.class,()->OperationalWaitingTime.eligibleSeconds(List.of(new OperationalWaitingTime.Span(start,start.plusSeconds(3662L*86400),true,false))));
 }
}
