package app.warden.api;

/** A process spawned by the server, with its identity. */
interface IRemoteProcess {
    ParcelFileDescriptor getOutputStream();
    ParcelFileDescriptor getInputStream();
    ParcelFileDescriptor getErrorStream();
    int waitFor();
    int exitValue();
    boolean alive();
    void destroy();
}
