package io.github.brick.web.domain.player;

import io.github.brick.data.lock.*;
import io.github.brick.data.store.DataKeys;
import io.github.brick.web.domain.account.*;
import io.github.brick.web.auth.SessionIdentity;
import io.github.brick.web.api.GameException;
import io.github.brick.web.log.*;
import java.time.Clock;
import java.util.*;

public class PlayerService {
    private final LockScope locks;
    private final MongoAccountRepository accounts;
    private final BizLogger logger;
    private final Clock clock;
    public PlayerService(LockScope locks,MongoAccountRepository accounts,BizLogger logger,Clock clock) {
        this.locks=locks; this.accounts=accounts; this.logger=logger; this.clock=clock;
    }
    public PlayerProfile ensureInitialized(long accountId,String requestId) {
        Account initial=accounts.findById(accountId).orElseThrow(GameException::integrity);
        try(var ctx=locks.lockAll(List.of(LockReq.of("account",accountId),LockReq.of("player",initial.playerId())))) {
            Account current=accounts.findById(accountId).orElseThrow(GameException::integrity);
            if(current.playerId()!=initial.playerId()) throw GameException.integrity();
            String key=key(current.playerId());
            PlayerProfile profile=ctx.get(key,PlayerProfile.class);
            if(profile==null) {
                if(current.playerInitialized()) throw GameException.integrity();
                profile=new PlayerProfile(current.playerId(),accountId,"Player_"+current.playerId(),current.createdAt(),current.createdAt());
                log(requestId,profile,"CREATE_PLAYER",Map.of(),Map.of("displayName",profile.displayName()));
                ctx.put(key,profile);
            }
            checkOwner(profile,accountId,current.playerId());
            if(!current.playerInitialized()) accounts.markPlayerInitialized(accountId,current.playerId());
            return profile;
        }
    }
    public PlayerProfile get(SessionIdentity identity) {
        try(var ctx=locks.lockAll(List.of(LockReq.of("player",identity.uid())))) {
            return owned(ctx,identity);
        }
    }
    public PlayerProfile rename(SessionIdentity identity,String displayName,String requestId) {
        if(displayName==null || displayName.codePoints().anyMatch(c->Character.isISOControl(c) || c==0x2028 || c==0x2029)) throw GameException.invalid();
        String name=displayName.strip(); int length=name.codePointCount(0,name.length());
        if(length<1 || length>24) throw GameException.invalid();
        try(var ctx=locks.lockAll(List.of(LockReq.of("player",identity.uid())))) {
            PlayerProfile old=owned(ctx,identity);
            if(old.displayName().equals(name)) return old;
            var updated=new PlayerProfile(old.uid(),old.accountId(),name,old.createdAt(),clock.millis());
            log(requestId,updated,"RENAME",Map.of("displayName",old.displayName()),Map.of("displayName",name));
            ctx.put(key(identity.uid()),updated);
            return updated;
        }
    }
    private PlayerProfile owned(LockCtx ctx,SessionIdentity identity) {
        var profile=ctx.get(key(identity.uid()),PlayerProfile.class);
        if(profile==null) throw GameException.integrity();
        checkOwner(profile,identity.accountId(),identity.uid());
        return profile;
    }
    private void checkOwner(PlayerProfile profile,long accountId,long uid) {
        if(profile.accountId()!=accountId || profile.uid()!=uid) throw new GameException(403,"FORBIDDEN","无权访问该角色");
    }
    private void log(String requestId,PlayerProfile profile,String operation,Map<String,String> before,Map<String,String> after) {
        logger.append(new BizEvent(UUID.randomUUID().toString(),requestId,clock.millis(),profile.accountId(),profile.uid(),operation,before,after));
    }
    private String key(long uid) { return DataKeys.key("player",uid,"profile"); }
}
