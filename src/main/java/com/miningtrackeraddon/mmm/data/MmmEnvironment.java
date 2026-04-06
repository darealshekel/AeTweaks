package com.miningtrackeraddon.mmm.data;

public enum MmmEnvironment
{
    SERVER("Server Digs"),
    SINGLEPLAYER("Singleplayer Digs"),
    UNKNOWN("Other Digs");

    private final String label;

    MmmEnvironment(String label)
    {
        this.label = label;
    }

    public String getLabel()
    {
        return this.label;
    }
}
