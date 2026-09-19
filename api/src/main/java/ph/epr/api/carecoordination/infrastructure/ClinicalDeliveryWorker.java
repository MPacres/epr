package ph.epr.api.carecoordination.infrastructure;
import java.time.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import ph.epr.api.identitytenancy.*;
import ph.epr.api.platformoperations.TenantTransactions;
import ph.epr.api.platformoperations.TenantTransactions.Context;
@Configuration(proxyBeanMethods=false)
@EnableScheduling
@Profile("local")
@ConditionalOnProperty(name={"epr.clinical.enabled","epr.interventions.worker-enabled"},havingValue="true")
public class ClinicalDeliveryWorker {
 private final ph.epr.api.documentscommunication.PrivateClinicalArtifacts artifacts;
 private final ClinicalDeliveryScopes scopes;private final TenantTransactions tenants;private final JdbcClinicalWorklist worklist;
 public ClinicalDeliveryWorker(ClinicalDeliveryScopes scopes,TenantTransactions tenants,JdbcClinicalWorklist worklist,ph.epr.api.documentscommunication.PrivateClinicalArtifacts artifacts){this.artifacts=artifacts;this.scopes=scopes;this.tenants=tenants;this.worklist=worklist;}
 public record DeliveryStatus(int attempts,OffsetDateTime nextAttemptAt,OffsetDateTime lastSuccessAt,String lastErrorCode) {}
 @Scheduled(fixedDelayString="${epr.interventions.delivery-poll-ms:30000}") public void poll(){
  for(var scope:scopes.enabled())try{
   tenants.execute(new AuthenticatedActor(scope.username()),scope.practiceId(),scope.siteId(),false,c->{deliver(c);return null;});
  }catch(RuntimeException failure){
   try{tenants.execute(new AuthenticatedActor(scope.username()),scope.practiceId(),scope.siteId(),false,c->{
    c.jdbc().sql("INSERT INTO clinical_delivery_checkpoint(practice_id,site_id,actor_id,attempts,next_attempt_at,last_error_code) VALUES(:p,:s,:a,1,CURRENT_TIMESTAMP+INTERVAL '30 seconds','DELIVERY_RETRY_REQUIRED') ON CONFLICT(practice_id,site_id,actor_id) DO UPDATE SET attempts=clinical_delivery_checkpoint.attempts+1,next_attempt_at=CURRENT_TIMESTAMP+LEAST(3600,30*power(2,LEAST(clinical_delivery_checkpoint.attempts,7))) * INTERVAL '1 second',last_error_code='DELIVERY_RETRY_REQUIRED'")
     .param("p",c.practiceId()).param("s",c.siteId()).param("a",c.actorId()).update();return null;
   });}catch(RuntimeException revokedOrUnavailable){/* No bypass of an unavailable or revoked scope; source obligations remain durable. */}
  }
 }
 public void deliver(Context c){
  var claim=c.jdbc().sql("SELECT pg_try_advisory_xact_lock(hashtextextended(:k,0))").param("k",c.practiceId()+"/delivery/"+c.siteId()+"/"+c.actorId()).query(Boolean.class).single();
  if(!claim)return;
  var ready=c.jdbc().sql("SELECT NOT EXISTS(SELECT 1 FROM clinical_delivery_checkpoint WHERE practice_id=:p AND site_id=:s AND actor_id=:a AND next_attempt_at>CURRENT_TIMESTAMP)")
   .param("p",c.practiceId()).param("s",c.siteId()).param("a",c.actorId()).query(Boolean.class).single();if(!ready)return;
  worklist.reconcile(c);
  artifacts.cleanupUnattached(c);
  c.jdbc().sql("INSERT INTO clinical_delivery_checkpoint(practice_id,site_id,actor_id,last_success_at) VALUES(:p,:s,:a,CURRENT_TIMESTAMP) ON CONFLICT(practice_id,site_id,actor_id) DO UPDATE SET attempts=0,last_success_at=CURRENT_TIMESTAMP,next_attempt_at=CURRENT_TIMESTAMP,last_error_code=NULL")
   .param("p",c.practiceId()).param("s",c.siteId()).param("a",c.actorId()).update();
 }
}
