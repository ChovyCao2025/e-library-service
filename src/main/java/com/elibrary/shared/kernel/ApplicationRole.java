package com.elibrary.shared.kernel;

/** 应用程式部署角色，决定目前实例是否承担背景工作。 */
public enum ApplicationRole {
    API(false),
    WORKER(true),
    ALL(true);

    private final boolean workerEnabled;

    ApplicationRole(boolean workerEnabled) {
        this.workerEnabled = workerEnabled;
    }

    /** 判断目前实例是否执行背景补偿与到期处理。 */
    public boolean isWorkerEnabled() {
        return workerEnabled;
    }
}
