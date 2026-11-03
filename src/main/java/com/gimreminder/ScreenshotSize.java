package com.gimreminder;

public enum ScreenshotSize
{
    SMALL("Small (640 px)", 640),
    MEDIUM("Medium (960 px)", 960);

    private final String label;
    private final int maxWidth;

    ScreenshotSize(String label, int maxWidth)
    {
        this.label = label;
        this.maxWidth = maxWidth;
    }

    public int maxWidth()
    {
        return maxWidth;
    }

    @Override public String toString()
    {
        return label;
    }
}
