package io.github.brick.web.config;
import com.mongodb.client.MongoClient;
import io.github.brick.data.codec.JsonCodec;
import io.github.brick.data.config.DataProperties;
import io.github.brick.data.idgen.IdGenerator;
import io.github.brick.data.lock.LockScope;
import io.github.brick.web.api.*;
import io.github.brick.web.auth.*;
import io.github.brick.web.domain.account.MongoAccountRepository;
import io.github.brick.web.domain.player.PlayerService;
import io.github.brick.web.log.*;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.protobuf.ProtobufHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;

@Configuration
@EnableConfigurationProperties(WebProperties.class)
public class WebConfiguration implements WebMvcConfigurer{
    @Bean Clock clock(){return Clock.systemUTC();}
    @Bean SecureRandom secureRandom(){return new SecureRandom();}
    @Bean PasswordHasher passwordHasher(SecureRandom random){return new PasswordHasher(random);}
    @Bean MongoAccountRepository accounts(MongoClient mongo,DataProperties p){return new MongoAccountRepository(mongo,p.getMongoDb());}
    @Bean SessionStore sessions(RedissonClient redis,JsonCodec codec,SecureRandom random,Clock clock,WebProperties p){return new SessionStore(redis,codec,random,clock,p);}
    @Bean LoginRateLimiter limiter(RedissonClient redis,WebProperties p){return new LoginRateLimiter(redis,p);}
    @Bean(destroyMethod="close") FileBizLogger bizLogger(WebProperties p,Clock clock){return new FileBizLogger(Path.of(p.getBizLogDirectory()),clock);}
    @Bean PlayerService players(LockScope locks,MongoAccountRepository accounts,BizLogger logger,Clock clock){return new PlayerService(locks,accounts,logger,clock);}
    @Bean AuthService auth(MongoAccountRepository accounts,PasswordHasher passwords,IdGenerator ids,PlayerService players,SessionStore sessions,Clock clock){return new AuthService(accounts,passwords,ids,players,sessions,clock);}
    @Bean ApiErrorWriter apiErrors(){return new ApiErrorWriter();}
    @Bean FilterRegistrationBean<RequestBodyLimitFilter> requestBoundary(ApiErrorWriter errors){var bean=new FilterRegistrationBean<>(new RequestBodyLimitFilter(errors));bean.setOrder(-200);bean.addUrlPatterns("/api/*");return bean;}
    @Bean FilterRegistrationBean<AuthFilter> authentication(SessionStore sessions,LoginRateLimiter limiter){var bean=new FilterRegistrationBean<>(new AuthFilter(sessions,limiter));bean.setOrder(-100);bean.addUrlPatterns("/api/*");return bean;}
    @Override public void extendMessageConverters(List<HttpMessageConverter<?>> converters){converters.addFirst(new ProtobufHttpMessageConverter());}
}
