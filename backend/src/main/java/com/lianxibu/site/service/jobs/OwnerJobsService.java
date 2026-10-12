package com.lianxibu.site.service.jobs;

import com.lianxibu.site.service.identity.IdentityService;
import com.lianxibu.site.service.identity.IdentityService.Actor;
import java.util.Objects;

/** Jobs API bridge. A missing permanent command provider refuses writes explicitly. */
public final class OwnerJobsService implements JobsService {
    public interface Commands {
        Job create(Actor actor,JobCreate command,String key,String csrf,String origin);
        ActionResult action(Actor actor,String id,JobAction command,String key,String csrf,String origin);
    }
    private final OwnerJobsReadService reads;
    private final Commands commands;
    public OwnerJobsService(OwnerJobsReadService reads,Commands commands){this.reads=Objects.requireNonNull(reads);this.commands=commands;}
    @Override public JobPage list(Actor actor,String kind,String type,long page,int size){owner(actor);return reads.list(actor,kind,type,page,size);}
    @Override public Job get(Actor actor,String id){owner(actor);return reads.get(actor,id);}
    @Override public Job create(Actor actor,JobCreate command,String key,String csrf,String origin){write(actor,key,csrf,origin);if(command==null)throw new IllegalArgumentException("job command required");return commands().create(actor,command,key,csrf,origin);}
    @Override public ActionResult action(Actor actor,String id,JobAction command,String key,String csrf,String origin){write(actor,key,csrf,origin);if(command==null)throw new IllegalArgumentException("job action required");return commands().action(actor,id,command,key,csrf,origin);}
    private Commands commands(){if(commands==null)throw new IdentityService.Rejected(IdentityService.Refusal.TEMPORARILY_UNAVAILABLE,"1");return commands;}
    private static void owner(Actor actor){if(actor==null||actor.userId()==null||actor.userId().isBlank())throw new IdentityService.Rejected(IdentityService.Refusal.UNAUTHENTICATED,null);if(!"owner".equals(actor.role()))throw new IdentityService.Rejected(IdentityService.Refusal.FORBIDDEN,null);}
    private static void write(Actor actor,String key,String csrf,String origin){owner(actor);if(key==null||!key.matches("[!-~]{1,128}")||csrf==null||csrf.isBlank()||origin==null||origin.isBlank())throw new IdentityService.Rejected(IdentityService.Refusal.FORBIDDEN,null);}
}
