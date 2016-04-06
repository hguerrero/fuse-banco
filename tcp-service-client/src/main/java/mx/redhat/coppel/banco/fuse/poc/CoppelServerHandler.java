package mx.redhat.coppel.banco.fuse.poc;

import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.component.netty4.NettyConsumer;
import org.apache.camel.component.netty4.NettyHelper;
import org.apache.camel.component.netty4.handlers.ServerChannelHandler;
import org.apache.camel.util.CamelLogger;
import org.apache.camel.util.IOHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

public class CoppelServerHandler extends ServerChannelHandler 
{
	private static final Logger LOG = LoggerFactory.getLogger(NettyConsumer.class);
	
	private final NettyConsumer cons;
	private final CamelLogger noReplyLogger;
	
	private boolean handShakeFlag;
	private int totalSize;
	private boolean responseFlag;
	private int countPackages = 1;
	private int packageSizeReversed;
	private StringBuilder message = new StringBuilder();
	
	private final int PACKAGE_SIZE = 196;
	
	public CoppelServerHandler(NettyConsumer consumer) 
	{
		super(consumer);
		cons = consumer;
		noReplyLogger = new CamelLogger(LOG, consumer.getConfiguration().getNoReplyLogLevel());
	}
	
	@Override
	protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception 
	{
		ByteBuf in = (ByteBuf)msg;
		if (!in.isReadable()) 
		{
			return;
		}
		
		if (responseFlag) 
		{
			int sizeAknowledge = in.readInt();
			
			LOG.info("Acuse recibido: " + reverseInteger(sizeAknowledge));
			LOG.info("Longitud de la cadena: " + message.length());
			
			if(message.length() > 0) 
			{
				int tamanoPackage = PACKAGE_SIZE;
				if(message.length() < PACKAGE_SIZE) {
					tamanoPackage = message.length();
					LOG.info("El tamano del buffer es menor al predeterminado: " + tamanoPackage);
				}
				
				String bodyPackage = message.toString().substring(0, tamanoPackage);
				
				message.delete(0, tamanoPackage);
				
				LOG.info("Paquetes " + countPackages + " size: " + tamanoPackage + " body: " + bodyPackage);
				
				ByteBuf out = Unpooled.buffer(4 + tamanoPackage);
				out.writeInt(reverseInteger(tamanoPackage));
				out.writeBytes(bodyPackage.getBytes());
				
				ctx.channel().writeAndFlush(out);
				
				countPackages++;
			} 
			else 
			{
				if (LOG.isTraceEnabled()) 
                {
                    LOG.trace("Closing channel as no more payload to send as reply at address: {}", ctx.channel().remoteAddress());
                }
                NettyHelper.close(ctx.channel());
			}
			return;
		}
		
		if (!handShakeFlag) 
		{
			int totalSizeReversed = in.readInt();
			
			totalSize = reverseInteger(totalSizeReversed);
			
			LOG.info("Total Size received: " + totalSize);
			
			handShakeFlag = true;
			
			ByteBuf out = Unpooled.buffer(4);
			out.writeInt(totalSizeReversed);
			
			ctx.channel().writeAndFlush(out);
			
			LOG.info("End of handshake");
			
			return;
		}
		
		LOG.info("Data Read");
		
		packageSizeReversed = in.readInt();
		
		LOG.info("PackageR " + countPackages + " size: " + reverseInteger(packageSizeReversed));

		LOG.info("Entrando para obtener el body del paquete");
		
		String bodyPackage = new String(in.readBytes(reverseInteger(packageSizeReversed)).array());
		
		LOG.info("PackageR " + countPackages + " size: " + reverseInteger(packageSizeReversed) + "; body: " + bodyPackage);
		
		message.append(bodyPackage);
		
		countPackages++;
		
		ByteBuf out = Unpooled.buffer(4);
		out.writeInt(packageSizeReversed);
		
		ctx.channel().writeAndFlush(out);
		
		if(message.length() != totalSize) 
		{
			return;
		}
		
		out = Unpooled.buffer(totalSize);
		out.writeBytes(message.toString().getBytes());
		
        if (LOG.isDebugEnabled()) {
            LOG.debug("Channel: {} received body: {}", new Object[]{ctx.channel(), message});
        }
        
        // create Exchange and let the consumer process it
        final Exchange exchange = cons.getEndpoint().createExchange(ctx, message);
        if (cons.getConfiguration().isSync()) {
            exchange.setPattern(ExchangePattern.InOut);
        }
        // set the exchange charset property for converting
        if (cons.getConfiguration().getCharsetName() != null) {
            exchange.setProperty(Exchange.CHARSET_NAME, IOHelper.normalizeCharset(cons.getConfiguration().getCharsetName()));
        }

        // we want to handle the UoW
        cons.createUoW(exchange);
        
        beforeProcess(exchange, ctx, msg);
		
        // process accordingly to endpoint configuration
        if (cons.getEndpoint().isSynchronous()) 
        {
            processSynchronously(exchange, ctx, msg);
        } 
        else {
            // No Op
        	//processAsynchronously(exchange, ctx, msg);
        }        
	}
	
	private void processSynchronously(final Exchange exchange, final ChannelHandlerContext ctx, final Object message) 
	{
        try {
            cons.getProcessor().process(exchange);
            if (cons.getConfiguration().isSync()) {
                sendResponse(message, ctx, exchange);
            }
        } catch (Throwable e) {
            cons.getExceptionHandler().handleException(e);
        } finally {
            cons.doneUoW(exchange);
        }
    }
	
	private void sendResponse(Object msg, ChannelHandlerContext ctx, Exchange exchange) throws Exception 
	{
        LOG.info("Aqui debemos enviar la respuesta");
    	
        Object body = getResponseBody(exchange);
        
        if (body == null) 
        {
            noReplyLogger.log("No payload to send as reply for exchange: " + exchange);
            if (cons.getConfiguration().isDisconnectOnNoReply()) 
            {
                // must close session if no data to write otherwise client will never receive a response
                // and wait forever (if not timing out)
                if (LOG.isTraceEnabled()) 
                {
                    LOG.trace("Closing channel as no payload to send as reply at address: {}", ctx.channel().remoteAddress());
                }
                NettyHelper.close(ctx.channel());
            }
        } 
        else 
        {
        	// if textline enabled then covert to a String which must be used for textline
            if (cons.getConfiguration().isTextline()) 
            {
                String bodyAsString = NettyHelper.getTextlineBody(body, exchange, cons.getConfiguration().getDelimiter(), cons.getConfiguration().isAutoAppendDelimiter());

                // we got a body to write
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Channel: {} writing body: {}", new Object[]{ctx.channel(), body});
                }

                totalSize = bodyAsString.length();
            	
            	int totalSizeReversed = reverseInteger(totalSize);
            	
            	message.delete(0, message.length());
            	message.append(bodyAsString);
            	
            	responseFlag = true;
            	countPackages = 1;
            	
        		ByteBuf out = Unpooled.buffer(4);
        		out.writeInt(totalSizeReversed);
        		ctx.channel().writeAndFlush(out);
            }
            
        }
	}
	
	@Override
	protected Object getResponseBody(Exchange exchange) throws Exception 
	{
        String respuesta = exchange.getOut().getBody(String.class);
    	
        LOG.info("Lo que voy a enviar: " + respuesta);
        
        return respuesta;
	}
	
	private int reverseInteger(int input) {
		return (input >>> 24) | (input >> 8) & 0x0000ff00 | (input << 8) & 0x00ff0000 | (input << 24);
	}
}
 