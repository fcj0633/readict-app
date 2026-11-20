package com.readict.settings.infrastructure.persistence.entity;

import java.util.UUID;
import java.time.Instant;
import com.baomidou.mybatisplus.annotation.*;
import com.readict.shared.persistence.typehandler.*;

@TableName(value="user_settings", autoResultMap=true)
public class SettingsEntity {
    @TableId(type=IdType.INPUT)
    private UUID userId;
    private String font;
    private int fontSize;
    private java.math.BigDecimal lineHeight;
    private int contentWidth;
    private String theme;
    private String pronunciation;
    private long version;
    @TableField(typeHandler=UuidBinaryTypeHandler.class)
    private UUID lastRequestId;
    private byte[] lastRequestHash;
    @TableField(typeHandler=UtcInstantTypeHandler.class)
    private Instant updatedAt;
    public UUID getUserId() { return userId; }
    public void setUserId(UUID value) { userId=value; }
    public String getFont() { return font; }
    public void setFont(String value) { font=value; }
    public int getFontSize() { return fontSize; }
    public void setFontSize(int value) { fontSize=value; }
    public java.math.BigDecimal getLineHeight() { return lineHeight; }
    public void setLineHeight(java.math.BigDecimal value) { lineHeight=value; }
    public int getContentWidth() { return contentWidth; }
    public void setContentWidth(int value) { contentWidth=value; }
    public String getTheme() { return theme; }
    public void setTheme(String value) { theme=value; }
    public String getPronunciation() { return pronunciation; }
    public void setPronunciation(String value) { pronunciation=value; }
    public long getVersion() { return version; }
    public void setVersion(long value) { version=value; }
    public UUID getLastRequestId() { return lastRequestId; }
    public void setLastRequestId(UUID value) { lastRequestId=value; }
    public byte[] getLastRequestHash() { return lastRequestHash; }
    public void setLastRequestHash(byte[] value) { lastRequestHash=value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt=value; }
}
