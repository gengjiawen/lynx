// Copyright 2026 The Lynx Authors. All rights reserved.
// Licensed under the Apache License Version 2.0 that can be found in the
// LICENSE file in the root directory of this source tree.
package com.lynx.tasm.core.resource;

import androidx.annotation.Nullable;
import com.lynx.tasm.base.CalledByNative;
import com.lynx.tasm.base.LLog;
import com.lynx.tasm.core.LynxThreadPool;
import com.lynx.tasm.utils.UIThreadUtils;
import java.nio.charset.StandardCharsets;

/** Bundle fetch decisions provided by the optional Host Script library. */
final class LynxHostScriptResourceHooks {
  private static final String TAG = "LynxHostScriptResourceHooks";

  private static volatile boolean sAvailable;

  private LynxHostScriptResourceHooks() {}

  @CalledByNative
  private static void setAvailable() {
    sAvailable = true;
  }

  static boolean hasHandler(long viewId) {
    return sAvailable && viewId >= 0 && nativeHasHandler(viewId);
  }

  /**
   * Takes over a lazy or frame bundle request when a handler is registered for its View.
   *
   * <p>Returns false to leave the request to the original path untouched.
   */
  static boolean intercept(final LynxResourceLoader loader, final long viewId,
      final long responseHandler, final String url, final int type, boolean reqInCurThread) {
    if ((type != LynxResourceType.LYNX_RESOURCE_TYPE_JS_LAZY_BUNDLE
            && type != LynxResourceType.LYNX_RESOURCE_TYPE_FRAME)
        || url == null || !hasHandler(viewId)) {
      return false;
    }
    // Only a synchronous UI caller can resume the load inline after the decision.
    final boolean loadInline = reqInCurThread && UIThreadUtils.isOnUiThread();
    UIThreadUtils.runOnUiThreadImmediately(new Runnable() {
      @Override
      public void run() {
        final Object decision = dispatch(viewId, url);
        Runnable resume = new Runnable() {
          @Override
          public void run() {
            if (decision instanceof byte[]) {
              loader.deliverMockedBundle(responseHandler, url, type, (byte[]) decision);
            } else {
              loader.loadResource(
                  responseHandler, decision instanceof String ? (String) decision : url, type);
            }
          }
        };
        if (loadInline) {
          resume.run();
          return;
        }
        try {
          LynxThreadPool.getBriefIOExecutor().execute(resume);
        } catch (Throwable e) {
          // to release native pointer if exception occurs
          LynxResourceLoader.InvokeNativeCallbackWithBytes(
              responseHandler, null, LynxResourceLoader.RESOURCE_LOADER_FAILED, e.getMessage());
        }
      }
    });
    return true;
  }

  // On UI. Falls back to the original request if dispatch fails.
  @Nullable
  private static Object dispatch(long viewId, String url) {
    try {
      return nativeDispatch(viewId, url.getBytes(StandardCharsets.UTF_8));
    } catch (Throwable e) {
      LLog.e(TAG, "Host Script resource.fetch failed: " + e.getMessage());
      return null;
    }
  }

  private static native boolean nativeHasHandler(long viewId);

  // On UI. Returns a redirect String, mock byte[], or null to continue the original fetch.
  @Nullable private static native Object nativeDispatch(long viewId, byte[] url);
}
