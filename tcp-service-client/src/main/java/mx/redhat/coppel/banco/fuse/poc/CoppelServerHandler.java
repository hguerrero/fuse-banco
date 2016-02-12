package mx.redhat.coppel.banco.fuse.poc;

import java.net.SocketAddress;

import org.apache.camel.AsyncCallback;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.component.netty.NettyConsumer;
import org.apache.camel.component.netty.NettyHelper;
import org.apache.camel.component.netty.NettyPayloadHelper;
import org.apache.camel.component.netty.handlers.ServerResponseFutureListener;
import org.apache.camel.util.CamelLogger;
import org.apache.camel.util.IOHelper;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CoppelServerHandler extends SimpleChannelUpstreamHandler {
	private boolean handShakeFlag;
	private int totalSize;
	private boolean responseFlag;
	private int countPackages = 1;
	private int packageSizeReversed;
	private StringBuilder message = new StringBuilder();
	
	private final int PACKAGE_SIZE = 196;
	
	
    // use NettyConsumer as logger to make it easier to read the logs as this is part of the consumer
    private static final Logger LOG = LoggerFactory.getLogger(CoppelServerHandler.class);
    private final NettyConsumer consumer;
    private final CamelLogger noReplyLogger;

    public CoppelServerHandler(NettyConsumer consumer) {
        this.consumer = consumer;    
        this.noReplyLogger = new CamelLogger(LOG, consumer.getConfiguration().getNoReplyLogLevel());
    }

    @Override
    public void channelOpen(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Channel open: {}", e.getChannel());
        }
        // to keep track of open sockets
        consumer.getNettyServerBootstrapFactory().addChannel(e.getChannel());
       // make sure the event can be processed by other handlers
        super.channelOpen(ctx, e);
    }

    @Override
    public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Channel closed: {}", e.getChannel());
        }
        // to keep track of open sockets
        consumer.getNettyServerBootstrapFactory().removeChannel(e.getChannel());
        // make sure the event can be processed by other handlers
        super.channelClosed(ctx, e);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent exceptionEvent) throws Exception {
        // only close if we are still allowed to run
        if (consumer.isRunAllowed()) {
            // let the exception handler deal with it
            consumer.getExceptionHandler().handleException("Closing channel as an exception was thrown from Netty", exceptionEvent.getCause());
            // close channel in case an exception was thrown
            NettyHelper.close(exceptionEvent.getChannel());
        }
    }
    
//    @Override
//    public void messageReceived(final ChannelHandlerContext ctx, final MessageEvent messageEvent) throws Exception {
//        Object in = messageEvent.getMessage();
//        if (LOG.isDebugEnabled()) {
//            LOG.debug("Channel: {} received body: {}", new Object[]{messageEvent.getChannel(), in});
//        }
//
//        // create Exchange and let the consumer process it
//        final Exchange exchange = consumer.getEndpoint().createExchange(ctx, messageEvent);
//
//        if (consumer.getConfiguration().isSync()) {
//            exchange.setPattern(ExchangePattern.InOut);
//        }
//        // set the exchange charset property for converting
//        if (consumer.getConfiguration().getCharsetName() != null) {
//            exchange.setProperty(Exchange.CHARSET_NAME, IOHelper.normalizeCharset(consumer.getConfiguration().getCharsetName()));
//        }
//
//        // we want to handle the UoW
//        consumer.createUoW(exchange);
//
//        beforeProcess(exchange, messageEvent);
//
//        // process accordingly to endpoint configuration
//        if (consumer.getEndpoint().isSynchronous()) {
//            processSynchronously(exchange, messageEvent);
//        } else {
//            processAsynchronously(exchange, messageEvent);
//        }
//    }

    /**
     * Allows any custom logic before the {@link Exchange} is processed by the routing engine.
     *
     * @param exchange       the exchange
     * @param messageEvent   the Netty message event
     */
    protected void beforeProcess(final Exchange exchange, final MessageEvent messageEvent) {
        // noop
    }

    private void processSynchronously(final Exchange exchange, final MessageEvent messageEvent) {
        try {
            consumer.getProcessor().process(exchange);
            if (consumer.getConfiguration().isSync()) {
                sendResponse(messageEvent, exchange);
            }
        } catch (Throwable e) {
            consumer.getExceptionHandler().handleException(e);
        } finally {
            consumer.doneUoW(exchange);
        }
    }

    private void processAsynchronously(final Exchange exchange, final MessageEvent messageEvent) {
        consumer.getAsyncProcessor().process(exchange, new AsyncCallback() {
            @Override
            public void done(boolean doneSync) {
                // send back response if the communication is synchronous
                try {
                    if (consumer.getConfiguration().isSync()) {
                        sendResponse(messageEvent, exchange);
                    }
                } catch (Throwable e) {
                    consumer.getExceptionHandler().handleException(e);
                } finally {
                    consumer.doneUoW(exchange);
                }
            }
        });
    }

    private void sendResponse(MessageEvent messageEvent, Exchange exchange) throws Exception {
        Object body = getResponseBody(exchange);

        if (body == null) {
            noReplyLogger.log("No payload to send as reply for exchange: " + exchange);
            if (consumer.getConfiguration().isDisconnectOnNoReply()) {
                // must close session if no data to write otherwise client will never receive a response
                // and wait forever (if not timing out)
                if (LOG.isTraceEnabled()) {
                    LOG.trace("Closing channel as no payload to send as reply at address: {}", messageEvent.getRemoteAddress());
                }
                NettyHelper.close(messageEvent.getChannel());
            }
        } else {
            // if textline enabled then covert to a String which must be used for textline
            if (consumer.getConfiguration().isTextline()) {
                body = NettyHelper.getTextlineBody(body, exchange, consumer.getConfiguration().getDelimiter(), consumer.getConfiguration().isAutoAppendDelimiter());
            }

            // we got a body to write
            ChannelFutureListener listener = createResponseFutureListener(consumer, exchange, messageEvent.getRemoteAddress());
            if (consumer.getConfiguration().isTcp()) {
                NettyHelper.writeBodyAsync(LOG, messageEvent.getChannel(), null, body, exchange, listener);
            } else {
                NettyHelper.writeBodyAsync(LOG, messageEvent.getChannel(), messageEvent.getRemoteAddress(), body, exchange, listener);
            }
        }
    }

    /**
     * Gets the object we want to use as the response object for sending to netty.
     *
     * @param exchange the exchange
     * @return the object to use as response
     * @throws Exception is thrown if error getting the response body
     */
    protected Object getResponseBody(Exchange exchange) throws Exception {
        // if there was an exception then use that as response body
        boolean exception = exchange.getException() != null && !consumer.getEndpoint().getConfiguration().isTransferExchange();
        if (exception) {
            return exchange.getException();
        }
        if (exchange.hasOut()) {
            return NettyPayloadHelper.getOut(consumer.getEndpoint(), exchange);
        } else {
            return NettyPayloadHelper.getIn(consumer.getEndpoint(), exchange);
        }
    }

    /**
     * Creates the {@link ChannelFutureListener} to execute when writing the response is complete.
     *
     * @param consumer          the netty consumer
     * @param exchange          the exchange
     * @param remoteAddress     the remote address of the message
     * @return the listener.
     */
    protected ChannelFutureListener createResponseFutureListener(NettyConsumer consumer, Exchange exchange, SocketAddress remoteAddress) {
        return new ServerResponseFutureListener(consumer, exchange, remoteAddress);
    }
	

	@Override
	public void messageReceived(ChannelHandlerContext ctx, MessageEvent messageEvent) {
		
		ChannelBuffer buffer = (ChannelBuffer) messageEvent.getMessage();
		if (!buffer.readable()) {
			return;
		}
		
		if(!responseFlag) {
			if (!handShakeFlag) {
				int totalSizeReversed = buffer.readInt();
				totalSize = reverseInteger(totalSizeReversed);
				LOG.info("Total Size received: " + totalSize);
				handShakeFlag = true;
				buffer = ChannelBuffers.buffer(4);
				buffer.writeInt(totalSizeReversed);
				ctx.getChannel().write(buffer);
				return;
			}
			packageSizeReversed = buffer.readInt();
			LOG.info("PackageR " + countPackages + " size: " + reverseInteger(packageSizeReversed));

			LOG.info("Entrando para obtener el body del paquete");
			String bodyPackage = new String(buffer.readBytes(reverseInteger(packageSizeReversed)).array());
			LOG.info("PackageR " + countPackages + " size: " + reverseInteger(packageSizeReversed) + "; body: " + bodyPackage);
			message.append(bodyPackage);
			countPackages++;
			ChannelBuffer bufferWriter = ChannelBuffers.buffer(4);
			bufferWriter.writeInt(packageSizeReversed);
			ctx.getChannel().write(bufferWriter);
			
			if(message.length() == totalSize) {
				LOG.info("Message: " + message.toString());
				try {
					buffer = ChannelBuffers.buffer(totalSize);
					buffer.writeBytes(message.toString().getBytes());
					CoppelWrapperEvent wrapperEvent = new CoppelWrapperEvent(messageEvent, buffer);
			        final Exchange exchange = consumer.getEndpoint().createExchange(ctx, wrapperEvent);
			        if (consumer.getConfiguration().isSync()) {
			            exchange.setPattern(ExchangePattern.InOut);
			        }
			        // set the exchange charset property for converting
			        if (consumer.getConfiguration().getCharsetName() != null) {
			            exchange.setProperty(Exchange.CHARSET_NAME, IOHelper.normalizeCharset(consumer.getConfiguration().getCharsetName()));
			        }
			        // we want to handle the UoW
			        consumer.createUoW(exchange);
			        beforeProcess(exchange, wrapperEvent);
			        // process accordingly to endpoint configuration
			        if (consumer.getEndpoint().isSynchronous()) {
			        	LOG.info("sincrono");
			            try {
			                consumer.getProcessor().process(exchange);
			                if (consumer.getConfiguration().isSync()) {
			                    LOG.info("Aqui debemos enviar la respuesta");
			                	String respuesta = (String) exchange.getOut().getBody();
			                	LOG.info("Lo que voy a enviar: " + respuesta);
			                	
			                	totalSize = respuesta.length();
			                	int totalSizeReversed = reverseInteger(totalSize);
			                	message.delete(0, message.length());
			                	message.append(respuesta);
			                	responseFlag = true;
			                	countPackages = 1;
			    				buffer = ChannelBuffers.buffer(4);
			    				buffer.writeInt(totalSizeReversed);
			    				ctx.getChannel().write(buffer);
			                }
			            } catch (Throwable e) {
			                consumer.getExceptionHandler().handleException(e);
			            } finally {
			                consumer.doneUoW(exchange);
			            }
			        } else {
			            processAsynchronously(exchange, wrapperEvent);
			            LOG.info("asincrono");
			        }
				} catch(Throwable t) {
					t.printStackTrace();
				}
			}
			return;
		} else {
			int sizeAknowledge = buffer.readInt();
			LOG.info("Acuse recibido: " + reverseInteger(sizeAknowledge));
			LOG.info("Longitud de la cadena: " + message.length());
			if(message.length() > 0) {
				int tamanoPackage = PACKAGE_SIZE;
				if(message.length() < PACKAGE_SIZE) {
					tamanoPackage = message.length();
					LOG.info("El tamano del buffer es menor al predeterminado: " + tamanoPackage);
				}
				
				String bodyPackage = message.toString().substring(0, tamanoPackage);
				message.delete(0, tamanoPackage);
				LOG.info("Paquete S " + countPackages + " size: " + tamanoPackage + " body: " + bodyPackage);
				buffer = ChannelBuffers.buffer(4 + tamanoPackage);
				buffer.writeInt(reverseInteger(tamanoPackage));
				buffer.writeBytes(bodyPackage.getBytes());
				ctx.getChannel().write(buffer);
				countPackages++;
			} else {
				LOG.info("Se cierra la conversacion");
				ctx.getChannel().close();
			}
			return;
		}
	}

	private int reverseInteger(int input) {
		return (input >>> 24) | (input >> 8) & 0x0000ff00 | (input << 8) & 0x00ff0000 | (input << 24);
	}

}
 