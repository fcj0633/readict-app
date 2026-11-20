package com.readict.identity.api;

import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import jakarta.servlet.http.HttpServletRequest;
import com.readict.identity.application.VerificationService;

@RestController
@RequestMapping("/api/v1/auth")
public class VerificationController {
    public record Contact(String identifierType) {}
    public record Target(String identifierType,String identifier) {}
    public static class Proof {
        private UUID challengeId;private String proof;
        public UUID getChallengeId(){return challengeId;}public void setChallengeId(UUID value){challengeId=value;}
        public String getProof(){return proof;}public void setProof(String value){proof=value;}
    }
    public static class Complete {
        private String resetToken,newPassword;
        public String getResetToken(){return resetToken;}public void setResetToken(String v){resetToken=v;}
        public String getNewPassword(){return newPassword;}public void setNewPassword(String v){newPassword=v;}
    }
    private final VerificationService service;
    public VerificationController(VerificationService service){this.service=service;}
    @PostMapping("/verification/challenges") public ResponseEntity<Map<String,Object>> contact(@RequestBody Contact target,Authentication auth,HttpServletRequest req) {
        var value=service.requestContact(AuthController.principal(auth),target.identifierType(),req.getRemoteAddr());
        return ResponseEntity.accepted().body(Map.of("challengeId",value.challengeId(),"expiresIn",value.expiresIn(),"retryAfter",value.retryAfter(),"simulation",value.simulation()));
    }
    @PostMapping("/verification/verify") public Map<String,Object> verifyContact(@RequestBody Proof input,Authentication auth) {
        var value=service.verify(input.challengeId,input.proof,"VERIFY_CONTACT",AuthController.principal(auth));
        return Map.of("emailVerified",value.emailVerified(),"phoneVerified",value.phoneVerified(),"simulation",value.simulation());
    }
    @PostMapping("/password-reset/challenges") public ResponseEntity<Map<String,Object>> reset(@RequestBody Target target,HttpServletRequest req) {
        var value=service.requestReset(target.identifierType(),target.identifier(),req.getRemoteAddr());
        return ResponseEntity.accepted().body(Map.of("challengeId",value.challengeId(),"expiresIn",value.expiresIn(),"retryAfter",value.retryAfter(),"message",value.message()));
    }
    @PostMapping("/password-reset/verify") public Map<String,Object> verifyReset(@RequestBody Proof input) {
        var value=service.verify(input.challengeId,input.proof,"PASSWORD_RESET",null);
        return Map.of("resetToken",value.resetToken(),"expiresAt",value.expiresAt(),"simulation",value.simulation());
    }
    @PostMapping("/password-reset/complete") public ResponseEntity<Void> complete(@RequestBody Complete input) {service.complete(input.resetToken,input.newPassword);return ResponseEntity.noContent().build();}
}
