package io.github.rahuldandotiya.o2c8.xml;

/** XML namespaces used by Oracle BPM sources and Camunda 8 targets. */
public final class Ns {

  public static final String BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL";
  public static final String BPMNDI = "http://www.omg.org/spec/BPMN/20100524/DI";
  public static final String DC = "http://www.omg.org/spec/DD/20100524/DC";
  public static final String DI = "http://www.omg.org/spec/DD/20100524/DI";
  public static final String XSI = "http://www.w3.org/2001/XMLSchema-instance";
  public static final String XMLNS = "http://www.w3.org/2000/xmlns/";

  public static final String ZEEBE = "http://camunda.org/schema/zeebe/1.0";
  public static final String MODELER = "http://camunda.org/schema/modeler/1.0";

  /** Oracle BPM 11g/12c extension namespace. */
  public static final String ORACLE = "http://xmlns.oracle.com/bpm/OracleExtensions";

  private Ns() {}
}
