package com.ctrip.framework.apollo.portal.component;

import com.ctrip.framework.apollo.portal.component.config.PortalConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.UnsupportedEncodingException;

/**
 * FactoryBean类型的bean
 */
@Component
public class RestTemplateFactory implements FactoryBean<RestTemplate>, InitializingBean {

    @Autowired
    private HttpMessageConverters httpMessageConverters;

    @Autowired
    private PortalConfig portalConfig;

    private RestTemplate restTemplate;

    /**
     * 获取bean对象
     *
     * @return
     */
    @Override
    public RestTemplate getObject() {
        return restTemplate;
    }

    public Class<RestTemplate> getObjectType() {
        return RestTemplate.class;
    }

    public boolean isSingleton() {
        return true;
    }

    public void afterPropertiesSet() throws UnsupportedEncodingException {

        CloseableHttpClient httpClient = HttpClientBuilder.create().build();

        restTemplate = new RestTemplate(httpMessageConverters.getConverters());

        //设置http请求的连接超时时间和请求超时时间
        HttpComponentsClientHttpRequestFactory requestFactory =
                new HttpComponentsClientHttpRequestFactory(httpClient);

        requestFactory.setConnectTimeout(portalConfig.connectTimeout());
        requestFactory.setReadTimeout(portalConfig.readTimeout());

        restTemplate.setRequestFactory(requestFactory);
    }


}
