package com.readict.reading.application;

import com.readict.reading.api.ReadingProgressView;

public class ProgressConflictException extends RuntimeException {
    private final long currentVersion;
    private final ReadingProgressView serverProgress;

    public ProgressConflictException(long currentVersion, ReadingProgressView serverProgress) {
        super("阅读进度已被另一窗口更新");
        this.currentVersion = currentVersion;
        this.serverProgress = serverProgress;
    }

    public long currentVersion() { return currentVersion; }
    public ReadingProgressView serverProgress() { return serverProgress; }
}
