# Test fixtures

`claim-settlement/` is a small, hand-written Oracle SOA project (composite.xml, WADL,
WSDL, JCA adapter, config plan, two BPMN processes). It exists only so the tests can
cover outbound REST, SOAP and adapter references and calls between BPMN components,
which the real sample in `samples/` does not use. It is not a real-world process.
