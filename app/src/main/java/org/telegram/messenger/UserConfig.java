package org.telegram.messenger;

// EVE-SHIM
public class UserConfig {
    public static int selectedAccount = 0;

    public static UserConfig getInstance(int num) {
        return InstanceHolder.instances[num >= 0 && num < 4 ? num : 0];
    }

    private static class InstanceHolder {
        private static final UserConfig[] instances = new UserConfig[] {
            new UserConfig(), new UserConfig(), new UserConfig(), new UserConfig()
        };
    }

    public long getClientUserId() {
        return 0L;
    }
}
