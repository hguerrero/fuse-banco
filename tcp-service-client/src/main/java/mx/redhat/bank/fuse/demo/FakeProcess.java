package mx.redhat.bank.fuse.demo;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FakeProcess implements Processor 
{
	private static final Logger LOG = LoggerFactory.getLogger(FakeProcess.class);

	@Override
	public void process(Exchange exchange) throws Exception 
	{
		LOG.debug("Recibi el exchange");
		Object buffer = (Object) exchange.getIn().getBody();
		String mensaje = new String(buffer.toString());
		LOG.debug("Body=" + mensaje);
		exchange.getOut().setBody("2|PEREZ|LOPEZ|JUAN CARLOS|19750105|4|CULIACAN|CULIACAN|86|MORELOS|LAZARO CARDENAS|1696|18889999|S|0|0|0|0|0|0|0|0|/ESQ. RIO TEHUANTEPEC|7140397|6674742024|0|0|80170|433|#");
	}

}