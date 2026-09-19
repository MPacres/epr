package ph.epr.api.practiceadministration.web;

import java.util.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ph.epr.api.identitytenancy.*;
import ph.epr.api.identitytenancy.PracticeTeam.*;
import ph.epr.api.platformoperations.PracticeSiteDirectory;
import ph.epr.api.practiceadministration.application.TeamAdministration;

@RestController
@Profile("local")
@ConditionalOnProperty(name="epr.team.enabled",havingValue="true")
class TeamController {
    private final PracticeTeam team;
    private final PracticeSiteDirectory sites;
    private final TeamAdministration commands;
    TeamController(PracticeTeam team,PracticeSiteDirectory sites,TeamAdministration commands){this.team=team;this.sites=sites;this.commands=commands;}
    private AuthenticatedActor actor(Authentication auth,HttpServletResponse response){response.setHeader("Cache-Control","no-store");return new AuthenticatedActor(auth.getName());}
    @GetMapping("/api/v1/administration-scopes")
    List<TeamScope> scopes(Authentication auth,HttpServletResponse response){return team.scopes(actor(auth,response));}
    @GetMapping("/api/v1/practices/{practice}/administration/sites")
    List<PracticeSiteDirectory.TeamSite> sites(Authentication auth,HttpServletResponse response,@PathVariable UUID practice){return sites.sites(actor(auth,response),practice);}
    @GetMapping("/api/v1/practices/{practice}/administration/members")
    TeamPage members(Authentication auth,HttpServletResponse response,@PathVariable UUID practice,
        @RequestParam(defaultValue="")String search,@RequestParam(defaultValue="ALL")String role,@RequestParam(defaultValue="ALL")String status,
        @RequestParam(required=false)UUID site,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="25")int size){
        return team.members(actor(auth,response),practice,search,role,status,site,page,size);
    }
    @GetMapping("/api/v1/practices/{practice}/administration/members/{user}")
    TeamMember member(Authentication auth,HttpServletResponse response,@PathVariable UUID practice,@PathVariable UUID user){return team.member(actor(auth,response),practice,user);}
    record TeamLookup(String email){}
    @PostMapping("/api/v1/practices/{practice}/administration/member-resolution")
    TeamResolution resolve(Authentication auth,HttpServletResponse response,@PathVariable UUID practice,@RequestBody TeamLookup lookup){return team.resolve(actor(auth,response),practice,lookup.email());}
    @PostMapping("/api/v1/practices/{practice}/administration/members")
    TeamResult create(Authentication auth,HttpServletResponse response,@PathVariable UUID practice,@RequestHeader("Idempotency-Key")UUID key,@RequestBody TeamCreate command){return commands.create(actor(auth,response),practice,key,command);}
    @PutMapping("/api/v1/practices/{practice}/administration/members/{user}/access")
    TeamResult update(Authentication auth,HttpServletResponse response,@PathVariable UUID practice,@PathVariable UUID user,@RequestHeader("Idempotency-Key")UUID key,@RequestBody TeamAccess command){return commands.update(actor(auth,response),practice,user,key,command);}
    @PostMapping("/api/v1/practices/{practice}/administration/members/{user}/temporary-password")
    TeamResult password(Authentication auth,HttpServletResponse response,@PathVariable UUID practice,@PathVariable UUID user,@RequestHeader("Idempotency-Key")UUID key,@RequestBody TeamPassword command){return team.reissue(actor(auth,response),practice,user,key,command);}
    @GetMapping("/api/v1/practices/{practice}/administration/members/{user}/events")
    List<TeamEvent> events(Authentication auth,HttpServletResponse response,@PathVariable UUID practice,@PathVariable UUID user,@RequestParam(defaultValue="0")int page){return team.events(actor(auth,response),practice,user,page);}
    @ExceptionHandler(TeamException.class)
    ResponseEntity<ProblemDetail> problem(TeamException error){return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore()).body(ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(error.status()),error.getMessage()));}
}
