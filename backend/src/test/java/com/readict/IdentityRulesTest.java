package com.readict;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.readict.identity.domain.*;
import com.readict.identity.infrastructure.security.AuthConfiguration;
import static org.assertj.core.api.Assertions.*;

class IdentityRulesTest {
    @ParameterizedTest @ValueSource(strings={"13800138000","+8613800138000"," 13800138000 "," +8613800138000 "})
    void phoneFormatsShareCanonicalKey(String input) {assertThat(LoginIdentifier.parse("phone",input).canonicalKey()).isEqualTo("+8613800138000");}
    @ParameterizedTest @ValueSource(strings={"+12125551234","008613800138000","+86+8613800138000","138 0013 8000","138-0013-8000","１３８００１３８０００","12800138000","1380013800","8613800138000"})
    void rejectsUnsupportedPhones(String input) {assertThatThrownBy(() -> LoginIdentifier.parse("phone",input)).isInstanceOf(DomainProblem.class);}
    @Test void emailIdnaAndCaseAreCanonicalized() {
        assertThat(LoginIdentifier.parse("email"," User@EXAMPLE.com ").canonicalKey()).isEqualTo("user@example.com");
        assertThat(LoginIdentifier.parse("email","User@例子.中国").canonicalKey()).contains("xn--");
        assertThatThrownBy(() -> LoginIdentifier.parse("email","a..b@example.com")).isInstanceOf(DomainProblem.class);
    }
    @ParameterizedTest @ValueSource(strings={"abc12345","ABC12345","Abc12345","abc1234!"," a1234567 ","😀a123456"})
    void acceptsPasswordPolicyExamples(String input) {PasswordPolicy.validate(input);}
    @ParameterizedTest @ValueSource(strings={"abcdefgh","12345678","Abcdefgh","abc1234"})
    void rejectsWeakPasswordExamples(String input) {assertThatThrownBy(() -> PasswordPolicy.validate(input)).isInstanceOf(DomainProblem.class);}
    @Test void countsUnicodeAndNeverTrimsPassword() {
        PasswordPolicy.validate("a1"+"😀".repeat(126));
        assertThatThrownBy(() -> PasswordPolicy.validate("a1"+"😀".repeat(127))).isInstanceOf(DomainProblem.class);
        assertThatThrownBy(() -> PasswordPolicy.validate("a123456\uD800")).isInstanceOf(DomainProblem.class);
        var encoder=new AuthConfiguration().passwordEncoder();long start=System.nanoTime();
        String first=encoder.encode(" abc12345 ");String second=encoder.encode(" abc12345 ");
        assertThat(first).startsWith("{pbkdf2-r1}").isNotEqualTo(second);
        assertThat(encoder.matches(" abc12345 ",first)).isTrue();assertThat(encoder.matches("abc12345",first)).isFalse();
        assertThatThrownBy(() -> encoder.matches("abc12345","{unknown}hash")).isInstanceOf(IllegalArgumentException.class);
        System.out.println("PBKDF2 calibration: 2 encodes + 2 matches ms="+(System.nanoTime()-start)/1_000_000);
    }
    @Test void missingSecretsAndUnsafeLocalModesAreRejected() {
        assertThatThrownBy(() -> new com.readict.identity.infrastructure.security.AuthSecrets(new org.springframework.mock.env.MockEnvironment())).isInstanceOf(IllegalStateException.class);
        var environment=new org.springframework.mock.env.MockEnvironment().withProperty("readict.verification.sender","local").withProperty("readict.verification.local-enabled","true").withProperty("readict.verification.hmac-key","test-secret-00000000000000000000000000000000");
        environment.setActiveProfiles("prod");
        assertThatThrownBy(() -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(new com.readict.identity.infrastructure.verification.VerificationConfiguration(),"verificationSender",environment,java.time.Clock.systemUTC())).isInstanceOf(IllegalStateException.class);
    }

}
