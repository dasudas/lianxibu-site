package com.lianxibu.site.domain.sources;

import static org.junit.jupiter.api.Assertions.*;
import com.lianxibu.site.domain.sources.MemberUniverse.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MemberUniverseTest {
    static Member member(String item,String member,long part,String terminal){return new Member(item,member,"fictional-key","fictional-observation-"+member,"a".repeat(64),null,part,terminal);}
    @Test void exactCompleteAndEmptyUniversesAreImmutableAndPreserveExcludedMembers() {
        var members=new ArrayList<>(List.of(member("fictional-item-a","fictional-member-a",0,"accepted"),member("fictional-item-b","fictional-member-b",0,"excluded")));
        var u=new Universe("fictional-run","fictional-source","b".repeat(64),List.of(new Part(0,"c".repeat(64),2)),members,1,1);members.clear();
        assertEquals(2,u.members().size());assertThrows(UnsupportedOperationException.class,()->u.members().clear());
        assertEquals(0,new Universe("fictional-empty","fictional-source","b".repeat(64),List.of(),List.of(),0,0).members().size());
    }
    @Test void partialDuplicateUnorderedAndUnboundedUniversesReject() {
        var a=member("fictional-item-a","fictional-member-a",0,"accepted");var b=member("fictional-item-b","fictional-member-b",0,"excluded");
        assertThrows(IllegalArgumentException.class,()->new Universe("fictional-run","fictional-source","b".repeat(64),List.of(new Part(0,"c".repeat(64),2)),List.of(a),1,0));
        assertThrows(IllegalArgumentException.class,()->new Universe("fictional-run","fictional-source","b".repeat(64),List.of(new Part(0,"c".repeat(64),2)),List.of(a,a),2,0));
        assertThrows(IllegalArgumentException.class,()->new Universe("fictional-run","fictional-source","b".repeat(64),List.of(new Part(0,"c".repeat(64),2)),List.of(b,a),1,1));
        assertThrows(IllegalArgumentException.class,()->new Universe("fictional-run","fictional-source","b".repeat(64),List.of(new Part(1,"c".repeat(64),1)),List.of(a),1,0));
        assertThrows(IllegalArgumentException.class,()->member("fictional-item-a","fictional-member-a",0,"pending"));
        assertThrows(IllegalArgumentException.class,()->new Part(0,"c".repeat(64),101));
        assertThrows(IllegalArgumentException.class,()->new Member("fictional-item","fictional-member","x".repeat(4097),"fictional-observation","a".repeat(64),null,0,"accepted"));
        assertThrows(IllegalArgumentException.class,()->new Universe("fictional-run","fictional-source","b".repeat(64),List.of(),Collections.nCopies(10001,a),10001,0));
    }
}
