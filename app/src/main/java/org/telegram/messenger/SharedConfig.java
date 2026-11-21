package org.telegram.messenger;

// EVE-SHIM
public class SharedConfig {
    public static boolean useThreeLinesLayout = false;
    public static boolean archiveHidden = false;

    public static int getDevicePerformanceClass() {
        return 2;
    }

    public static final int PERFORMANCE_CLASS_HIGH = 2;
    public static final int PERFORMANCE_CLASS_AVERAGE = 1;
    public static final int PERFORMANCE_CLASS_LOW = 0;
}
