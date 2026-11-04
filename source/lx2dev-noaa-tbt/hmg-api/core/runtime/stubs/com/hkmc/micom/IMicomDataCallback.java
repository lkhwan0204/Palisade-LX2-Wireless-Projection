package com.hkmc.micom;

/**
 * Compile-only signature of the car's IMicomDataCallback (automotive.jar, on the boot
 * classpath): never in hmgapi-hook.jar; the car's own class is used at run time.
 */
public interface IMicomDataCallback extends android.os.IInterface {
    void onEvent(byte rcvId, byte funcH, byte funcL, byte[] data) throws android.os.RemoteException;

    abstract class Stub extends android.os.Binder implements IMicomDataCallback {
        public Stub() {}

        public android.os.IBinder asBinder() {
            return this;
        }
    }
}
