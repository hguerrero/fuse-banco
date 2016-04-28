package mx.redhat.bank.fuse.demo;

import org.apache.camel.component.netty4.NettyConsumer;
import org.apache.camel.component.netty4.ServerInitializerFactory;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;

public class BankServerPipelineFactory extends ServerInitializerFactory 
{
	private NettyConsumer consumer;

	public BankServerPipelineFactory() {
		super();
	}

	public BankServerPipelineFactory(NettyConsumer consumer) {
		this.consumer = consumer;
	}

	@Override
	public ServerInitializerFactory createPipelineFactory(NettyConsumer consumer) {
		return new BankServerPipelineFactory(consumer);
	}

	@Override
	protected void initChannel(Channel ch) throws Exception {
		ChannelPipeline channelPipeline = ch.pipeline();
		channelPipeline.addLast("handler", new BankServerHandler(consumer));
	}


}