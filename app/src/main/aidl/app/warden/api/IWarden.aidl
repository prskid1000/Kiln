package app.warden.api;

import app.warden.api.IRemoteProcess;

/**
 * The privileged server's interface. The server process runs as shell (uid 2000)
 * when ADB-bootstrapped, or root (uid 0) when started via su / the Zygisk module.
 * Every method is audited by the server before dispatch, and access is gated by
 * signature-bound, scoped grants.
 */
interface IWarden {
    int apiVersion();
    int serverUid();

    /**
     * Wrap a system-service binder so the caller's transactions execute with the
     * server's identity. Requires the "svc:<descriptor>" scope for that service.
     */
    IBinder transactAs(in IBinder target);

    /** Run a command with the server's identity. Requires the "exec" scope. */
    IRemoteProcess newProcess(in String[] cmd, in String[] env, in String dir);

    /** Current permission state for a package: 0 unknown, 1 granted, 2 denied. */
    int checkGrant(in String pkg);

    // ---- manager-only (authenticated by signing key) ----

    /** Grant scopes to a package for ttlMillis (0 = permanent). */
    void setGrant(in String pkg, in String[] scopes, long ttlMillis);
    void revokeGrant(in String pkg);
    /** JSON array of current grants for the manager UI. */
    String grantsJson();

    /** Stream the append-only, hash-chained audit log (JSONL). */
    ParcelFileDescriptor auditTail();

    /** Manager-only: stop the broker (the server process exits). */
    void shutdown();

    /** Manager-only: clear the audit log. */
    void clearAudit();
}
