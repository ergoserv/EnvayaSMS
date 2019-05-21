package org.envaya.sms.logs;

import android.content.Context;
import com.logentries.logger.AndroidLogger;
import java.io.IOException;
import org.envaya.sms.R;

public class LogentriesAndroidLogger {

  private static volatile AndroidLogger sInstance;

  //private constructor.
  private LogentriesAndroidLogger() {

    //Prevent form the reflection api.
    if (sInstance != null) {
      throw new RuntimeException(
          "Use getInstance() method to get the single instance of this class.");
    }
  }

  public static AndroidLogger getInstance(Context applicationContext) {
    if (sInstance == null) {
      synchronized (LogentriesAndroidLogger.class) {
        try {
          if (sInstance == null) {
            sInstance = AndroidLogger.createInstance(applicationContext,
                false,
                false,
                false,
                null,
                0,
                applicationContext.getString(R.string.logentry_key),
                true);
          }
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
    return sInstance;
  }
}
