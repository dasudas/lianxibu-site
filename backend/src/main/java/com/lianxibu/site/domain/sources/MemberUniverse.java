package com.lianxibu.site.domain.sources;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Complete original transfer membership. Acceptance alone does not establish AI or publication eligibility. */
public final class MemberUniverse {
    public static final int MAX_MEMBERS=10000,MAX_PARTS=10000;
    private MemberUniverse() { }
    @FunctionalInterface public interface Reader { Universe read(String runId); }
    public record Part(long partNo,String partHash,int memberCount) {
        public Part {if(partNo<0||partNo>=MAX_PARTS||memberCount<0||memberCount>100||!hash(partHash))throw invalid();}
    }
    public record Member(String itemId,String memberId,String sourceItemId,String observationId,String payloadHash,
                         String replacesObservationId,long partNo,String terminalState) {
        public Member {
            if(!id(itemId,64)||!id(memberId,64)||!id(observationId,64)||!hash(payloadHash)
                    ||replacesObservationId!=null&&!id(replacesObservationId,64)||partNo<0||partNo>=MAX_PARTS
                    ||!Set.of("accepted","excluded").contains(terminalState)
                    ||sourceItemId!=null&&(sourceItemId.isBlank()||sourceItemId.getBytes(StandardCharsets.UTF_8).length>4096))throw invalid();
        }
        @Override public String toString(){return "ManifestMember[redacted]";}
    }
    public record Universe(String runId,String sourceId,String manifestHash,List<Part> parts,List<Member> members,long accepted,long excluded) {
        public Universe {
            if(!id(runId,64)||!id(sourceId,80)||!hash(manifestHash)||parts==null||parts.size()>MAX_PARTS||members==null||members.size()>MAX_MEMBERS
                    ||accepted<0||excluded<0||accepted>members.size()||excluded!=members.size()-accepted)throw invalid();
            parts=List.copyOf(parts);members=List.copyOf(members);int[] counts=new int[parts.size()];
            for(int i=0;i<parts.size();i++)if(parts.get(i).partNo()!=i)throw invalid();
            var items=new HashSet<String>();var names=new HashSet<String>();Member previous=null;long actualAccepted=0;
            for(var m:members) {
                if(m==null||m.partNo()>=parts.size()||!items.add(m.itemId())||!names.add(m.memberId())
                        ||previous!=null&&(m.partNo()<previous.partNo()||m.partNo()==previous.partNo()&&m.memberId().compareTo(previous.memberId())<=0))throw invalid();
                counts[(int)m.partNo()]++;if("accepted".equals(m.terminalState()))actualAccepted++;previous=m;
            }
            if(actualAccepted!=accepted)throw invalid();
            for(int i=0;i<parts.size();i++)if(counts[i]!=parts.get(i).memberCount())throw invalid();
        }
        @Override public String toString(){return "MemberUniverse[redacted]";}
    }
    private static boolean id(String value,int limit){return value!=null&&value.matches("[A-Za-z0-9._:-]{1,"+limit+"}");}
    private static boolean hash(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid complete manifest membership");}
}
