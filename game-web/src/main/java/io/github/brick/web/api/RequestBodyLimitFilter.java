package io.github.brick.web.api;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.MDC;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Synchronous API boundary: reads at most LIMIT+1, including chunked or dishonest lengths. */
public final class RequestBodyLimitFilter implements Filter {
    private static final int LIMIT=16*1024;
    private final ApiErrorWriter errors;
    public RequestBodyLimitFilter(ApiErrorWriter errors){this.errors=errors;}
    @Override public void doFilter(ServletRequest raw,ServletResponse rawResponse,FilterChain chain)throws IOException,ServletException{
        var request=(HttpServletRequest)raw;var response=(HttpServletResponse)rawResponse;
        String requestId=UUID.randomUUID().toString();MDC.put("requestId",requestId);
        response.setHeader("X-Request-Id",requestId);response.setHeader("Cache-Control","no-store");
        try{
            if(request.getContentLengthLong()>LIMIT)throw tooLarge();
            byte[] bytes=request.getInputStream().readNBytes(LIMIT+1);
            if(bytes.length>LIMIT)throw tooLarge();
            // Container /api/* mapping already selected this filter. Never bypass on the raw URI:
            // Tomcat/MVC normalize encoded segments and matrix parameters before routing.
            String path=request.getRequestURI().substring(request.getContextPath().length());
            if(!path.matches("/api(?:/[A-Za-z0-9_-]+)+"))
                throw new GameException(400,"INVALID_PATH","请使用规范 API 路径");
            chain.doFilter(new BufferedRequest(request,bytes),response);
        }catch(Exception error){errors.failure(response,error);}
        finally{MDC.remove("requestId");}
    }
    private GameException tooLarge(){return new GameException(413,"PAYLOAD_TOO_LARGE","请求体超过 16 KiB");}
    private static final class BufferedRequest extends HttpServletRequestWrapper{
        private final byte[] bytes;
        BufferedRequest(HttpServletRequest request,byte[] bytes){super(request);this.bytes=bytes;}
        @Override public int getContentLength(){return bytes.length;}
        @Override public long getContentLengthLong(){return bytes.length;}
        @Override public ServletInputStream getInputStream(){
            var source=new ByteArrayInputStream(bytes);
            return new ServletInputStream(){
                public int read(){return source.read();}
                public int read(byte[] b,int off,int len){return source.read(b,off,len);}
                public boolean isFinished(){return source.available()==0;}
                public boolean isReady(){return true;}
                public void setReadListener(ReadListener listener){throw new UnsupportedOperationException("Synchronous API only");}
            };
        }
        @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
    }
}
