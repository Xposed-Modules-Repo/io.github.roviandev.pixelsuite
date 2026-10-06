package io.github.pixelsuite;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flashlight control from system_server. The torch state is tracked with a TorchCallback,
 * so it stays in sync with the Quick Settings tile and any other app using the torch.
 * All camera calls run on a private background thread, never on the input thread.
 */
final class Torch {

    private static Handler sHandler;
    private static CameraManager sCameraManager;
    private static volatile String sFlashId;
    private static final Map<String, Boolean> sState = new ConcurrentHashMap<String, Boolean>();

    private Torch() {}

    static synchronized void init(Context ctx) {
        if (sCameraManager != null) return;
        HandlerThread thread = new HandlerThread("PowerTorch");
        thread.start();
        sHandler = new Handler(thread.getLooper());
        sCameraManager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        // Delivers the current state of every flash unit right after registration.
        sCameraManager.registerTorchCallback(new CameraManager.TorchCallback() {
            @Override
            public void onTorchModeChanged(String cameraId, boolean enabled) {
                sState.put(cameraId, enabled);
            }

            @Override
            public void onTorchModeUnavailable(String cameraId) {
                sState.put(cameraId, Boolean.FALSE);
            }
        }, sHandler);
    }

    static synchronized Handler handler() {
        return sHandler;
    }

    /** Flips the rear flashlight, then runs {@code after} on the torch thread. */
    static void toggle(Context ctx, final Runnable after) {
        init(ctx);
        sHandler.post(new Runnable() {
            private int mWaits;

            @Override
            public void run() {
                String id = flashId();
                if (id == null) {
                    Module.log("no camera with a flash unit found");
                    return;
                }
                Boolean on = sState.get(id);
                if (on == null && mWaits++ < 4) {
                    // State not delivered yet (first use since boot): wait briefly.
                    sHandler.postDelayed(this, 100L);
                    return;
                }
                boolean target = !(on != null && on);
                long token = Binder.clearCallingIdentity();
                try {
                    sCameraManager.setTorchMode(id, target);
                    if (after != null) after.run();
                } catch (Throwable t) {
                    // e.g. camera in use by an app: the torch is unavailable then.
                    Module.log("setTorchMode(" + target + ") failed", t);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        });
    }

    private static String flashId() {
        String cached = sFlashId;
        if (cached != null) return cached;
        long token = Binder.clearCallingIdentity();
        try {
            String anyWithFlash = null;
            for (String id : sCameraManager.getCameraIdList()) {
                CameraCharacteristics c = sCameraManager.getCameraCharacteristics(id);
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (flash == null || !flash) continue;
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraMetadata.LENS_FACING_BACK) {
                    sFlashId = id;
                    return id;
                }
                if (anyWithFlash == null) anyWithFlash = id;
            }
            sFlashId = anyWithFlash;
            return anyWithFlash;
        } catch (Throwable t) {
            Module.log("camera id lookup failed", t);
            return null;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }
}
