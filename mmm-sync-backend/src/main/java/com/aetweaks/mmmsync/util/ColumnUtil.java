package com.aetweaks.mmmsync.util;

public final class ColumnUtil
{
    private ColumnUtil()
    {
    }

    public static int columnToIndex(String column)
    {
        int result = 0;
        for (char ch : column.trim().toUpperCase().toCharArray())
        {
            if (ch < 'A' || ch > 'Z')
            {
                throw new IllegalArgumentException("Invalid column: " + column);
            }
            result = result * 26 + (ch - 'A' + 1);
        }
        return result - 1;
    }
}
