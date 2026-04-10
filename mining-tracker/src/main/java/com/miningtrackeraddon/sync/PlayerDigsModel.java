package com.miningtrackeraddon.sync;

public record PlayerDigsModel(
        String username,
        long totalDigs,
        long capturedAtMs,
        String server,
        String objectiveTitle
)
{
    public boolean isValid()
    {
        return this.username != null
                && this.username.isBlank() == false
                && this.server != null
                && this.server.isBlank() == false
                && this.totalDigs >= 0L;
    }

    public boolean sameValue(PlayerDigsModel other)
    {
        return other != null
                && this.username.equalsIgnoreCase(other.username)
                && this.totalDigs == other.totalDigs
                && this.server.equalsIgnoreCase(other.server);
    }

    public PlayerDigsModel withServer(String nextServer)
    {
        return new PlayerDigsModel(
                this.username,
                this.totalDigs,
                this.capturedAtMs,
                nextServer,
                this.objectiveTitle
        );
    }
}
