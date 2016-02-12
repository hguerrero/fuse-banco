package mx.redhat.coppel.banco.fuse.poc;

import org.apache.camel.component.netty.NettyConsumer;
import org.apache.camel.component.netty.ServerPipelineFactory;
import org.jboss.netty.channel.ChannelHandler;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.Channels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CoppelServerPipelineFactory extends ServerPipelineFactory {

	private static final Logger LOG = LoggerFactory.getLogger(CoppelServerPipelineFactory.class);

	private NettyConsumer consumer;

	public CoppelServerPipelineFactory() {
		super();
		LOG.info("Construyendo: CoppelServerPipelineFactory");
	}

	public CoppelServerPipelineFactory(NettyConsumer consumer) {
		this.consumer = consumer;
	}

	@Override
	public ChannelPipeline getPipeline() throws Exception {
		ChannelPipeline channelPipeline = Channels.pipeline();
		addToPipeline("handler", channelPipeline, new CoppelServerHandler(consumer));
		return channelPipeline;
	}

	@Override
	public ServerPipelineFactory createPipelineFactory(NettyConsumer consumer) {
		return new CoppelServerPipelineFactory(consumer);
	}

	private void addToPipeline(String name, ChannelPipeline pipeline, ChannelHandler handler) {
		pipeline.addLast(name, handler);
	}


}