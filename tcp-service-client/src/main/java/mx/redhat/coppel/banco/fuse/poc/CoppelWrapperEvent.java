package mx.redhat.coppel.banco.fuse.poc;

import java.net.SocketAddress;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.MessageEvent;

public class CoppelWrapperEvent implements MessageEvent {

	private MessageEvent event;
	private ChannelBuffer content;
	

	public CoppelWrapperEvent(MessageEvent event, ChannelBuffer content) {
		this.event = event;
		this.content = content;
	}

	public Object getMessage() {
		return content;
	}

	public SocketAddress getRemoteAddress() {
		return event.getRemoteAddress();
	}

	public Channel getChannel() {
		return event.getChannel();
	}

	public ChannelFuture getFuture() {
		return event.getFuture();
	}

}