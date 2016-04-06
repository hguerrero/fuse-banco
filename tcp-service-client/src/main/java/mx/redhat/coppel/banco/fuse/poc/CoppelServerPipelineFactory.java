package mx.redhat.coppel.banco.fuse.poc;

import org.apache.camel.component.netty4.NettyConsumer;
import org.apache.camel.component.netty4.ServerInitializerFactory;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;

public class CoppelServerPipelineFactory extends ServerInitializerFactory 
{
	private NettyConsumer consumer;

	public CoppelServerPipelineFactory() {
		super();
	}

	public CoppelServerPipelineFactory(NettyConsumer consumer) {
		this.consumer = consumer;
	}

	@Override
	public ServerInitializerFactory createPipelineFactory(NettyConsumer consumer) {
		return new CoppelServerPipelineFactory(consumer);
	}

	@Override
	protected void initChannel(Channel ch) throws Exception {
		ChannelPipeline channelPipeline = ch.pipeline();
		channelPipeline.addLast("handler", new CoppelServerHandler(consumer));
	}


}