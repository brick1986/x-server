package io.github.brick.web.api;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.slf4j.MDC;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class RequestBodyLimitFilterTest {
    @Test void limitsActualStreamEvenWithMissingOrFalseLength() throws Exception {
        for(int claimed:new int[]{-1,1}) {
            var request=new MockHttpServletRequest("POST","/api/auth/login") {
                @Override public int getContentLength(){return claimed;}
                @Override public long getContentLengthLong(){return claimed;}
            };
            request.setContent(new byte[16385]); var response=new MockHttpServletResponse(); var reached=new AtomicBoolean();
            new RequestBodyLimitFilter(new ApiErrorWriter()).doFilter(request,response,(a,b)->reached.set(true));
            assertThat(response.getStatus()).isEqualTo(413); assertThat(reached).isFalse();
        }
    }
    @Test void boundaryAndErrorPathsClearOnlyRequestContext() throws Exception {
        MDC.put("unrelated","keep");
        try {
            var request=new MockHttpServletRequest("POST","/api/auth/login"); request.setContent(new byte[16384]);
            var response=new MockHttpServletResponse(); var reached=new AtomicBoolean();
            new RequestBodyLimitFilter(new ApiErrorWriter()).doFilter(request,response,(a,b)->{
                reached.set(true); assertThat(MDC.get("requestId")).isNotBlank(); throw new IllegalStateException("secret-value");
            });
            assertThat(reached).isTrue(); assertThat(response.getStatus()).isEqualTo(500);
            assertThat(response.getContentAsString()).doesNotContain("secret-value");
            assertThat(MDC.get("requestId")).isNull(); assertThat(MDC.get("unrelated")).isEqualTo("keep");
        } finally { MDC.clear(); }
    }
    @Test void mappedApiAliasesStillEnforceTheStreamLimit() throws Exception {
        for(String uri:new String[]{"/api;x/auth/register","/%61pi/auth/register"}) {
            var request=new MockHttpServletRequest("POST",uri);
            request.setContent(new byte[16385]);
            var response=new MockHttpServletResponse();
            var reached=new AtomicBoolean();
            new RequestBodyLimitFilter(new ApiErrorWriter()).doFilter(request,response,(a,b)->reached.set(true));
            assertThat(response.getStatus()).as(uri).isEqualTo(413);
            assertThat(reached).isFalse();
        }
    }
}
