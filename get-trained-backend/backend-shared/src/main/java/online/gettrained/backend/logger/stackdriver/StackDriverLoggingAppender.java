/*
 * Copyright 2017 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package online.gettrained.backend.logger.stackdriver;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.util.Loader;
import com.google.api.client.util.Preconditions;
import com.google.api.core.InternalApi;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.MonitoredResource;
import com.google.cloud.logging.LogEntry;
import com.google.cloud.logging.Logging;
import com.google.cloud.logging.Logging.WriteOption;
import com.google.cloud.logging.LoggingEnhancer;
import com.google.cloud.logging.LoggingOptions;
import com.google.cloud.logging.MonitoredResourceUtil;
import com.google.cloud.logging.Payload;
import com.google.cloud.logging.Severity;
import com.google.cloud.logging.logback.LoggingAppender;
import com.google.cloud.logging.logback.LoggingEventEnhancer;
import com.google.common.base.Strings;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A full copy of {@link LoggingAppender}, changed only a start method to be able obtaining a
 * credential JSON from a given place, but not only from env. variable.
 */
public class StackDriverLoggingAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

  private static final String LEVEL_NAME_KEY = "levelName";
  private static final String LEVEL_VALUE_KEY = "levelValue";

  private volatile Logging logging;
  private List<LoggingEnhancer> loggingEnhancers;
  private List<LoggingEventEnhancer> loggingEventEnhancers;
  private WriteOption[] defaultWriteOptions;

  private Level flushLevel;
  private String log;
  private String resourceType;
  private Set<String> enhancerClassNames = new HashSet<>();
  private Set<String> loggingEventEnhancerClassNames = new HashSet<>();

  private String pathToJsonKeyCredential;
  private LoggingOptions loggingOptions;

  /**
   * Batched logging requests get immediately flushed for logs at or above this level.
   *
   * <p>Defaults to Error if not set.
   *
   * @param flushLevel Logback log level
   */
  public void setFlushLevel(Level flushLevel) {
    this.flushLevel = flushLevel;
  }

  /**
   * Sets the log filename.
   *
   * @param log filename
   */
  public void setLog(String log) {
    this.log = log;
  }

  /**
   * Sets the name of the monitored resource (Optional).
   *
   * <p>Must be a <a href="https://cloud.google.com/logging/docs/api/v2/resource-list">supported</a>
   * resource type. gae_app, gce_instance and container are auto-detected.
   *
   * <p>Defaults to "global"
   *
   * @param resourceType name of the monitored resource
   */
  public void setResourceType(String resourceType) {
    this.resourceType = resourceType;
  }

  /**
   * Add extra labels using classes that implement {@link LoggingEnhancer}.
   */
  public void addEnhancer(String enhancerClassName) {
    this.enhancerClassNames.add(enhancerClassName);
  }

  public void addLoggingEventEnhancer(String enhancerClassName) {
    this.loggingEventEnhancerClassNames.add(enhancerClassName);
  }

  public void setPathToJsonKeyCredential(String pathToJsonKeyCredential) {
    this.pathToJsonKeyCredential = pathToJsonKeyCredential;
  }

  private Level getFlushLevel() {
    return (flushLevel != null) ? flushLevel : Level.ERROR;
  }

  private String getLogName() {
    return (log != null) ? log : "java.log";
  }

  private MonitoredResource getMonitoredResource(String projectId) {
    return MonitoredResourceUtil.getResource(projectId, resourceType);
  }

  private List<LoggingEnhancer> getLoggingEnhancers() {
    return getEnhancers(enhancerClassNames);
  }

  private List<LoggingEventEnhancer> getLoggingEventEnhancers() {
    return getEnhancers(loggingEventEnhancerClassNames);
  }

  private <T> List<T> getEnhancers(Set<String> classNames) {
    List<T> loggingEnhancers = new ArrayList<>();
    if (classNames != null) {
      for (String enhancerClassName : classNames) {
        if (enhancerClassName != null) {
          T enhancer = getEnhancer(enhancerClassName);
          if (enhancer != null) {
            loggingEnhancers.add(enhancer);
          }
        }
      }
    }
    return loggingEnhancers;
  }

  @SuppressWarnings("unchecked")
  private <T> T getEnhancer(String enhancerClassName) {
    try {
      Class<T> clz =
          (Class<T>)
              Loader.loadClass(enhancerClassName.trim());
      return clz.newInstance();
    } catch (Exception ex) {
      // If we cannot create the enhancer we fallback to null
    }
    return null;
  }

  /**
   * Initialize and configure the cloud logging service.
   */
  @Override
  public synchronized void start() {
    if (isStarted()) {
      return;
    }
    Preconditions.checkArgument(!Strings.isNullOrEmpty(pathToJsonKeyCredential),
        "'pathToJsonKeyCredential' empty");

    try (FileInputStream fileInputStream = new FileInputStream(pathToJsonKeyCredential)) {
      LoggingOptions.Builder loggingOptionsBuilder = LoggingOptions.newBuilder();
      loggingOptionsBuilder.setCredentials(GoogleCredentials.fromStream(fileInputStream));
      loggingOptions = loggingOptionsBuilder.build();
      MonitoredResource resource = getMonitoredResource(loggingOptions.getProjectId());
      defaultWriteOptions =
          new WriteOption[]{WriteOption.logName(getLogName()), WriteOption.resource(resource)};
      getLogging().setFlushSeverity(severityFor(getFlushLevel()));
      loggingEnhancers = new ArrayList<>();
      List<LoggingEnhancer> resourceEnhancers = MonitoredResourceUtil.getResourceEnhancers();
      loggingEnhancers.addAll(resourceEnhancers);
      loggingEnhancers.addAll(getLoggingEnhancers());
      loggingEventEnhancers = new ArrayList<>();
      loggingEventEnhancers.addAll(getLoggingEventEnhancers());

      super.start();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }


  @Override
  protected void append(ILoggingEvent e) {
    LogEntry logEntry = logEntryFor(e);
    getLogging().write(Collections.singleton(logEntry), defaultWriteOptions);
  }

  @Override
  public synchronized void stop() {
    if (logging != null) {
      try {
        logging.close();
      } catch (Exception ex) {
        // ignore
      }
    }
    logging = null;
    super.stop();
  }

  Logging getLogging() {
    if (logging == null) {
      synchronized (this) {
        if (logging == null) {
          logging = loggingOptions.getService();
        }
      }
    }
    return logging;
  }

  private LogEntry logEntryFor(ILoggingEvent e) {
    StringBuilder payload = new StringBuilder(e.getFormattedMessage()).append('\n');
    writeStack(e.getThrowableProxy(), "", payload);

    Level level = e.getLevel();
    LogEntry.Builder builder =
        LogEntry.newBuilder(Payload.StringPayload.of(payload.toString().trim()))
            .setTimestamp(e.getTimeStamp())
            .setSeverity(severityFor(level));

    builder
        .addLabel(LEVEL_NAME_KEY, level.toString())
        .addLabel(LEVEL_VALUE_KEY, String.valueOf(level.toInt()));

    if (loggingEnhancers != null) {
      for (LoggingEnhancer enhancer : loggingEnhancers) {
        enhancer.enhanceLogEntry(builder);
      }
    }

    if (loggingEventEnhancers != null) {
      for (LoggingEventEnhancer enhancer : loggingEventEnhancers) {
        enhancer.enhanceLogEntry(builder, e);
      }
    }

    return builder.build();
  }

  @InternalApi("Visible for testing")
  static void writeStack(IThrowableProxy throwProxy, String prefix, StringBuilder payload) {
    if (throwProxy == null) {
      return;
    }
    payload
        .append(prefix)
        .append(throwProxy.getClassName())
        .append(": ")
        .append(throwProxy.getMessage())
        .append('\n');
    StackTraceElementProxy[] trace = throwProxy.getStackTraceElementProxyArray();
    if (trace == null) {
      trace = new StackTraceElementProxy[0];
    }

    int commonFrames = throwProxy.getCommonFrames();
    int printFrames = trace.length - commonFrames;
    for (int i = 0; i < printFrames; i++) {
      payload.append("    ").append(trace[i]).append('\n');
    }
    if (commonFrames != 0) {
      payload.append("    ... ").append(commonFrames).append(" common frames elided\n");
    }

    writeStack(throwProxy.getCause(), "caused by: ", payload);
  }

  /**
   * Transforms Logback logging levels to Cloud severity.
   *
   * @param level Logback logging level
   * @return Cloud severity level
   */
  private static Severity severityFor(Level level) {
    switch (level.toInt()) {
      // TRACE
      case 5000:
        return Severity.DEBUG;
      // DEBUG
      case 10000:
        return Severity.DEBUG;
      // INFO
      case 20000:
        return Severity.INFO;
      // WARNING
      case 30000:
        return Severity.WARNING;
      // ERROR
      case 40000:
        return Severity.ERROR;
      default:
        return Severity.DEFAULT;
    }
  }
}
