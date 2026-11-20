package com.readict.identity.domain;

import java.nio.charset.StandardCharsets;

public final class PasswordPolicy {
    private PasswordPolicy() {}
    public static void validate(String password) {
        if (password==null || password.length()>256) throw invalid();
        int points=password.codePointCount(0,password.length());
        boolean digit=password.codePoints().anyMatch(c -> c>='0' && c<='9');
        boolean letter=password.codePoints().anyMatch(c -> c>='a' && c<='z' || c>='A' && c<='Z');
        if(points<8 || points>128 || !digit || !letter || password.getBytes(StandardCharsets.UTF_8).length>512) throw invalid();
        // Reject unpaired UTF-16 surrogates rather than silently hashing replacement bytes.
        for(int i=0;i<password.length();i++) {
            char c=password.charAt(i);
            if(Character.isHighSurrogate(c)) { if(++i>=password.length() || !Character.isLowSurrogate(password.charAt(i))) throw invalid(); }
            else if(Character.isLowSurrogate(c)) throw invalid();
        }
    }
    private static DomainProblem invalid() { return new DomainProblem(422,"PASSWORD_POLICY","密码不少于 8 位，且至少包含一个数字和一个英文字母"); }
}
