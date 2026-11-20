package com.readict.settings.api;

import java.math.BigDecimal;
import java.util.Set;
import com.readict.identity.domain.DomainProblem;
import com.readict.settings.infrastructure.persistence.entity.SettingsEntity;

public record Preferences(String font, int fontSize, BigDecimal lineHeight, int width, String theme, String pronunciation) {
    public void validate() {
        if(font==null || theme==null || pronunciation==null || lineHeight==null
            || !Set.of("Georgia","Palatino Linotype","Arial").contains(font) || !Set.of(18,20,22,24).contains(fontSize)
            || !Set.of(660,720).contains(width) || !Set.of("white","warm","dark").contains(theme)
            || !Set.of("en-GB","en-US").contains(pronunciation)
            || !Set.of(new BigDecimal("1.6"),new BigDecimal("1.8"),new BigDecimal("2.0")).contains(lineHeight.setScale(1,java.math.RoundingMode.UNNECESSARY)))
            throw new DomainProblem(422,"SETTINGS_INVALID","偏好设置不在可选范围内");
    }
    public static Preferences from(SettingsEntity s) { return new Preferences(s.getFont(),s.getFontSize(),s.getLineHeight(),s.getContentWidth(),s.getTheme(),s.getPronunciation()); }
    public String canonical() { return font+"|"+fontSize+"|"+lineHeight.stripTrailingZeros().toPlainString()+"|"+width+"|"+theme+"|"+pronunciation; }
}
