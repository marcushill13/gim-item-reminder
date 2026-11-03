package com.gimreminder;

/** Which storage movements may send a snapshot of the game canvas to Discord. */
public enum ScreenshotMode
{
    OFF("Off (recommended until group members opt in)"),
    BORROW_ONLY("On borrow only"),
    BORROW_AND_RETURN("On borrow and return");

    private final String label;

    ScreenshotMode(String label)
    {
        this.label = label;
    }

    @Override public String toString()
    {
        return label;
    }
}
