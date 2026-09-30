package io.github.rahuldandotiya.o2c8;

/**
 * Settings that change how Oracle constructs are mapped. Defaults produce models that deploy and
 * start on Camunda 8 without extra work.
 *
 * @param executionPlatformVersion Camunda 8 version written into the model (Modeler uses it for linting)
 * @param userTaskImplementation Camunda user tasks (recommended, 8.5+) or job-worker based user tasks
 * @param interfaceEvents how Oracle "define interface" (SOAP-exposed) start/end events are converted
 * @param correlationKeyPlaceholder FEEL used for message subscriptions whose key cannot be inferred
 */
public record ConverterOptions(
    String executionPlatformVersion,
    UserTaskImplementation userTaskImplementation,
    InterfaceEvents interfaceEvents,
    String correlationKeyPlaceholder) {

  public enum UserTaskImplementation {
    CAMUNDA_USER_TASK,
    JOB_WORKER
  }

  public enum InterfaceEvents {
    /** None start / none end event. Start the process via API; result via "create with result". */
    NONE,
    /** Message start event (named "&lt;processId&gt;.start"); end event stays a none end event. */
    MESSAGE
  }

  public static ConverterOptions defaults() {
    return new ConverterOptions(
        "8.6.0", UserTaskImplementation.CAMUNDA_USER_TASK, InterfaceEvents.NONE, "=correlationKey");
  }

  public ConverterOptions withUserTaskImplementation(UserTaskImplementation u) {
    return new ConverterOptions(executionPlatformVersion, u, interfaceEvents, correlationKeyPlaceholder);
  }

  public ConverterOptions withInterfaceEvents(InterfaceEvents i) {
    return new ConverterOptions(executionPlatformVersion, userTaskImplementation, i, correlationKeyPlaceholder);
  }

  public ConverterOptions withExecutionPlatformVersion(String v) {
    return new ConverterOptions(v, userTaskImplementation, interfaceEvents, correlationKeyPlaceholder);
  }
}
