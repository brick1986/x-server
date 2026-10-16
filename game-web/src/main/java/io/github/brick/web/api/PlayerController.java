package io.github.brick.web.api;
import io.github.brick.contract.game.*;
import io.github.brick.web.auth.RequestIdentity;
import io.github.brick.web.domain.player.PlayerService;
import io.github.brick.web.codec.ProfileMapper;
import org.springframework.web.bind.annotation.*;
import org.slf4j.MDC;
@RestController
@RequestMapping(value="/api/player",produces="application/x-protobuf",consumes="application/x-protobuf")
public class PlayerController{
    private final PlayerService players;
    public PlayerController(PlayerService players){this.players=players;}
    @PostMapping("/profile")
    public ProfileResponse get(@RequestBody(required=false) ProfileRequest ignored){return ProfileResponse.newBuilder().setProfile(ProfileMapper.toMessage(players.get(RequestIdentity.require()))).build();}
    @PostMapping("/rename")
    public ProfileResponse rename(@RequestBody RenameRequest request){return ProfileResponse.newBuilder().setProfile(ProfileMapper.toMessage(players.rename(RequestIdentity.require(),request.getDisplayName(),MDC.get("requestId")))).build();}
}
