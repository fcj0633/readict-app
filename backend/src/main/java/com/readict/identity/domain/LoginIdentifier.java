package com.readict.identity.domain;

import java.net.IDN;
import java.util.Locale;

public record LoginIdentifier(String type, String displayValue, String canonicalKey) {
    public static LoginIdentifier parse(String type, String input) {
        if (input == null || input.length()>254) throw invalid();
        String value=input.strip();
        if ("phone".equals(type)) {
            String local=value.startsWith("+86") ? value.substring(3) : value;
            if (!local.matches("1[3-9][0-9]{9}")) throw invalid();
            return new LoginIdentifier(type,"+86"+local,"+86"+local);
        }
        if (!"email".equals(type)) throw invalid();
        int at=value.lastIndexOf('@');
        if (at<1 || at!=value.indexOf('@') || at>64) throw invalid();
        String local=value.substring(0,at);
        if (!local.matches("[A-Za-z0-9!#$%&'*+/=?^_`{|}~.-]+") || local.startsWith(".") || local.endsWith(".") || local.contains("..")) throw invalid();
        try {
            String domain=IDN.toASCII(value.substring(at+1),IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (!domain.contains(".") || domain.startsWith(".") || domain.endsWith(".")) throw invalid();
            String display=local+"@"+domain;
            if (display.length()>254) throw invalid();
            return new LoginIdentifier(type,display,display.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) { throw invalid(); }
    }
    private static DomainProblem invalid() { return new DomainProblem(422,"IDENTIFIER_INVALID","账号格式有误；手机号仅支持中国大陆 +86"); }
}
