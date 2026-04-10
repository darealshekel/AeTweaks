package com.miningtrackeraddon.sync;

import java.util.List;

public record SourceScanResult(
        boolean compatible,
        String sourceName,
        String sourceKind,
        String host,
        String scoreboardTitle,
        List<String> sampleSidebarLines,
        List<String> detectedStatFields,
        int confidence,
        long totalDigs,
        long playerTotalDigs,
        String scanFingerprint,
        String iconUrl
)
{
    public boolean hasMeaningfulEvidence()
    {
        return this.compatible
                || (this.scoreboardTitle != null && this.scoreboardTitle.isBlank() == false)
                || (this.sampleSidebarLines != null && this.sampleSidebarLines.isEmpty() == false)
                || (this.detectedStatFields != null && this.detectedStatFields.isEmpty() == false)
                || this.totalDigs > 0L
                || this.playerTotalDigs > 0L;
    }
}
